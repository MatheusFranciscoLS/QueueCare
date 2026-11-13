package com.queuecare.security;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Limitador de janela fixa, em memória, de UMA instância da aplicação (MVP): conta eventos por chave dentro de uma janela de tempo.
 * O relógio é injetado (testes determinísticos).
 *
 * <p><b>Memória limitada, sem apagar bloqueios.</b> O número de chaves nunca passa de {@code maxKeys} (limite RÍGIDO): toda
 * chave NOVA entra por um único ponto, sob trava, que só admite se houver espaço. Para abrir espaço, descartam-se primeiro as
 * janelas vencidas (cada uma com a SUA duração) e depois, se preciso, as janelas vivas que NÃO estão bloqueadas (contagem
 * abaixo do limite), as de menor contagem primeiro, até sobrar 90% do espaço. Uma janela BLOQUEADA (contagem no limite) viva
 * NUNCA é descartada para dar lugar a outra chave. Se o mapa estiver cheio só de bloqueios vivos, a chave nova é RECUSADA
 * (devolve {@code false}, ou seja, 429): o sistema fecha em vez de abrir.
 *
 * <p><b>Custo limitado.</b> Abrir espaço exige varrer o mapa, então QUALQUER varredura (com sucesso ou não) só pode acontecer
 * uma vez por segundo; dentro desse intervalo, chave nova com o mapa cheio é recusada. Assim, nem mesmo um mapa cheio de
 * bloqueios com uma única chave expulsável consegue provocar varreduras repetidas. O {@code Retry-After} de uma recusa por
 * capacidade é o tempo até o próximo bloqueio vencer (ou o fim do intervalo da varredura), não um "1 segundo" fixo.
 * Para encher o mapa assim seria preciso levar 100 mil chaves distintas até o limite dentro da mesma janela.
 *
 * <p>O bloqueio por tentativas de login usa uma instância PRÓPRIA, para que o tráfego comum jamais dispute espaço com ele.
 * Em mais de uma instância os limites valem por instância; um limitador compartilhado (ex.: Redis) ou o do proxy é a
 * evolução natural.
 */
public final class RateLimiter {

	/** Nomes dos dois limitadores da aplicação (ver {@link RateLimitConfig}). */
	public static final String REQUEST_LIMITER = "requestRateLimiter";
	public static final String LOGIN_LOCKOUT_LIMITER = "loginLockoutLimiter";

	static final int DEFAULT_MAX_KEYS = 100_000;
	private static final long FULL_SCAN_COOLDOWN_MILLIS = 1_000;

	private record Window(long startMillis, long windowMillis, long count, int limit) {
		boolean expired(long now) {
			return now - startMillis >= windowMillis;
		}

		/** Bloqueada: já usou todas as permissões da janela (e a janela ainda vale). */
		boolean blocked(long now) {
			return !expired(now) && count >= limit;
		}

		/** Soma 1 sem estourar: ao chegar em Long.MAX_VALUE fica ali (nunca vira negativo). */
		Window incremented(int newLimit) {
			return new Window(startMillis, windowMillis, count == Long.MAX_VALUE ? count : count + 1, newLimit);
		}
	}

	private final Clock clock;
	private final int maxKeys;
	private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();
	/** Trava de admissão: TODA chave nova entra aqui, então o tamanho nunca passa de maxKeys. */
	private final Object admissionLock = new Object();
	private volatile long nextFullScanAllowedAtMillis; // escrito sob admissionLock
	/** Quando o primeiro bloqueio vivo vence, se a última varredura não liberou espaço (0 se não se aplica). */
	private volatile long capacityFreeAtMillis;
	private long fullScans; // só para testes; escrito sob admissionLock

	public RateLimiter(Clock clock) {
		this(clock, DEFAULT_MAX_KEYS);
	}

	RateLimiter(Clock clock, int maxKeys) {
		this.clock = clock;
		this.maxKeys = maxKeys;
	}

	/**
	 * Consome, de forma ATÔMICA, uma permissão da chave; devolve {@code true} se ainda havia (até {@code limit} por janela).
	 * Com várias requisições simultâneas, no máximo {@code limit} recebem {@code true} por janela. Devolve {@code false}
	 * também quando a chave é nova e o mapa está cheio de bloqueios vivos (fecha em vez de abrir).
	 */
	public boolean tryAcquire(String key, int limit, Duration window) {
		long now = clock.millis();
		long windowMillis = window.toMillis();
		boolean[] allowed = {false};
		// Caminho comum: a chave já existe; só atualiza (nunca aumenta o mapa).
		boolean[] existed = {false};
		windows.computeIfPresent(key, (k, current) -> {
			existed[0] = true;
			return step(current, now, windowMillis, limit, allowed);
		});
		if (existed[0]) {
			return allowed[0];
		}
		// Chave nova: única porta de entrada do mapa.
		synchronized (admissionLock) {
			// Abrir espaço mexe no mapa, então é feito FORA do compute (que não admite alterar outras entradas).
			if (!windows.containsKey(key) && !makeRoom(now)) {
				return false; // cheio de bloqueios vivos: recusa, sem guardar a chave
			}
			windows.compute(key, (k, current) -> {
				if (current != null) { // outra thread a criou enquanto esperávamos a trava
					return step(current, now, windowMillis, limit, allowed);
				}
				allowed[0] = limit > 0;
				return new Window(now, windowMillis, 1, limit);
			});
		}
		return allowed[0];
	}

	private static Window step(Window current, long now, long windowMillis, int limit, boolean[] allowed) {
		if (current.expired(now)) {
			allowed[0] = limit > 0;
			return new Window(now, windowMillis, 1, limit);
		}
		if (current.count() >= limit) {
			return current;
		}
		allowed[0] = true;
		return current.incremented(limit);
	}

	public void clear(String key) {
		windows.remove(key);
	}

	/** Zera todos os contadores (usado pelos testes entre um caso e outro e em operação para limpar bloqueios). */
	public void clearAll() {
		windows.clear();
	}

	/** Segundos até a janela da chave terminar (mínimo 1), para o cabeçalho Retry-After. */
	public long retryAfterSeconds(String key, Duration window) {
		long now = clock.millis();
		Window current = windows.get(key);
		if (current == null) {
			// Chave recusada por falta de capacidade (nunca foi guardada): quando algo puder ser liberado.
			long remainingMillis = Math.max(nextFullScanAllowedAtMillis, capacityFreeAtMillis) - now;
			return Math.max(1, (remainingMillis + 999) / 1000);
		}
		long remainingMillis = current.startMillis() + current.windowMillis() - now;
		return Math.max(1, (remainingMillis + 999) / 1000);
	}

	int size() {
		return windows.size();
	}

	/** Quantas varreduras completas já aconteceram (para testes). */
	long fullScans() {
		synchronized (admissionLock) {
			return fullScans;
		}
	}

	/** Garante espaço para UMA chave nova (chamado sob {@code admissionLock}); {@code false} se não for possível. */
	private boolean makeRoom(long now) {
		if (windows.size() < maxKeys) {
			return true;
		}
		if (now < nextFullScanAllowedAtMillis) {
			return false; // varremos há menos de 1 s: não repetir o trabalho caro, mesmo que a última tenha liberado algo
		}
		fullScans++;
		nextFullScanAllowedAtMillis = now + FULL_SCAN_COOLDOWN_MILLIS;
		capacityFreeAtMillis = 0;
		windows.entrySet().removeIf(e -> e.getValue().expired(now));
		if (windows.size() < maxKeys) {
			return true;
		}
		// Ainda cheio de janelas vivas: sai quem NÃO está bloqueado, de menor contagem (e, empatando, a mais antiga).
		List<Map.Entry<String, Window>> evictable = new ArrayList<>();
		for (Map.Entry<String, Window> e : windows.entrySet()) {
			if (!e.getValue().blocked(now)) {
				evictable.add(e);
			}
		}
		evictable.sort(Comparator.<Map.Entry<String, Window>>comparingLong(e -> e.getValue().count())
				.thenComparingLong(e -> e.getValue().startMillis()));
		int toRemove = Math.min(evictable.size(), windows.size() - (int) (maxKeys * 0.9));
		for (int i = 0; i < toRemove; i++) {
			Map.Entry<String, Window> victim = evictable.get(i);
			windows.remove(victim.getKey(), victim.getValue());
		}
		if (windows.size() < maxKeys) {
			return true;
		}
		// Nada a liberar: só os bloqueios vivos vencendo abrem espaço. Guarda quando o primeiro vence (para o Retry-After).
		long earliest = Long.MAX_VALUE;
		for (Window w : windows.values()) {
			earliest = Math.min(earliest, w.startMillis() + w.windowMillis());
		}
		capacityFreeAtMillis = earliest == Long.MAX_VALUE ? 0 : earliest;
		return false;
	}
}
