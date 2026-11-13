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
 * <p><b>Memória limitada sem apagar bloqueios.</b> Ao passar de {@code maxKeys} chaves, primeiro descartam-se as entradas cuja
 * janela (cada uma com a SUA duração) já venceu; se ainda sobrar demais, descartam-se as de MENOR contagem (as mais fracas:
 * quem fez uma tentativa só), até sobrar 90% do limite. As de contagem alta, que são justamente as bloqueadas, são as últimas a
 * sair. Nunca se zera tudo: um atacante que inunde o mapa com chaves novas não consegue apagar os bloqueios existentes.
 * O bloqueio por falhas de login usa uma instância PRÓPRIA, para que o tráfego comum jamais dispute espaço com ele.
 *
 * <p>Em mais de uma instância os limites valem por instância; um limitador compartilhado (ex.: Redis) ou o do proxy é a
 * evolução natural.
 */
public final class RateLimiter {

	/** Nomes dos dois limitadores da aplicação (ver {@link RateLimitConfig}). */
	public static final String REQUEST_LIMITER = "requestRateLimiter";
	public static final String LOGIN_LOCKOUT_LIMITER = "loginLockoutLimiter";

	static final int DEFAULT_MAX_KEYS = 100_000;

	private record Window(long startMillis, long windowMillis, long count) {
		boolean expired(long now) {
			return now - startMillis >= windowMillis;
		}

		/** Soma 1 sem estourar: ao chegar em Long.MAX_VALUE fica ali (nunca vira negativo). */
		Window incremented() {
			return new Window(startMillis, windowMillis, count == Long.MAX_VALUE ? count : count + 1);
		}
	}

	private final Clock clock;
	private final int maxKeys;
	private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();
	private final Object evictionLock = new Object();

	public RateLimiter(Clock clock) {
		this(clock, DEFAULT_MAX_KEYS);
	}

	RateLimiter(Clock clock, int maxKeys) {
		this.clock = clock;
		this.maxKeys = maxKeys;
	}

	/**
	 * Consome, de forma ATÔMICA, uma permissão da chave; devolve {@code true} se ainda havia (até {@code limit} por janela).
	 * Com várias requisições simultâneas, no máximo {@code limit} recebem {@code true} por janela.
	 */
	public boolean tryAcquire(String key, int limit, Duration window) {
		long now = clock.millis();
		long windowMillis = window.toMillis();
		boolean[] allowed = {false};
		windows.compute(key, (k, current) -> {
			if (current == null || current.expired(now)) {
				allowed[0] = limit > 0;
				return new Window(now, windowMillis, 1);
			}
			if (current.count() >= limit) {
				return current;
			}
			allowed[0] = true;
			return current.incremented();
		});
		evictIfNeeded(now);
		return allowed[0];
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
		Window current = windows.get(key);
		if (current == null) {
			return 1;
		}
		long remainingMillis = current.startMillis() + current.windowMillis() - clock.millis();
		return Math.max(1, (remainingMillis + 999) / 1000);
	}

	int size() {
		return windows.size();
	}

	private void evictIfNeeded(long now) {
		if (windows.size() <= maxKeys) {
			return;
		}
		synchronized (evictionLock) {
			if (windows.size() <= maxKeys) {
				return;
			}
			windows.entrySet().removeIf(e -> e.getValue().expired(now));
			if (windows.size() <= maxKeys) {
				return;
			}
			// Ainda cheio de janelas vivas: sai primeiro quem tem MENOS tentativas (e, empatando, quem começou há mais tempo).
			int target = (int) (maxKeys * 0.9);
			List<Map.Entry<String, Window>> candidates = new ArrayList<>(windows.entrySet());
			candidates.sort(Comparator.<Map.Entry<String, Window>>comparingLong(e -> e.getValue().count())
					.thenComparingLong(e -> e.getValue().startMillis()));
			int toRemove = windows.size() - target;
			for (int i = 0; i < toRemove && i < candidates.size(); i++) {
				Map.Entry<String, Window> victim = candidates.get(i);
				windows.remove(victim.getKey(), victim.getValue());
			}
		}
	}
}
