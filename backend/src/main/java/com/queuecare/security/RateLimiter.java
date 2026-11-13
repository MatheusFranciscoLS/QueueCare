package com.queuecare.security;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Limitador de janela fixa, em memória, de UMA instância da aplicação (MVP): conta eventos por chave dentro de uma janela de tempo.
 * O relógio é injetado (testes determinísticos). A memória é limitada: ao passar de {@link #MAX_KEYS} chaves, as janelas vencidas são
 * descartadas e, se ainda assim passar, todos os contadores são zerados (o pior caso é um contador voltar ao início, nunca a
 * aplicação ficar sem memória). Em mais de uma instância os limites valem por instância; um limitador compartilhado (ex.: Redis)
 * ou o do proxy é a evolução natural.
 */
public final class RateLimiter {

	static final int MAX_KEYS = 100_000;

	private record Window(long startMillis, int count) {
	}

	private final Clock clock;
	private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();
	private final AtomicLong longestWindowMillis = new AtomicLong();

	public RateLimiter(Clock clock) {
		this.clock = clock;
	}

	/** Consome uma permissão da chave; devolve {@code true} se ainda havia (até {@code limit} por janela). */
	public boolean tryAcquire(String key, int limit, Duration window) {
		long now = clock.millis();
		long windowMillis = window.toMillis();
		boolean[] allowed = {false};
		windows.compute(key, (k, current) -> {
			if (current == null || now - current.startMillis() >= windowMillis) {
				allowed[0] = limit > 0;
				return new Window(now, 1);
			}
			if (current.count() >= limit) {
				return current;
			}
			allowed[0] = true;
			return new Window(current.startMillis(), current.count() + 1);
		});
		housekeeping(now, windowMillis);
		return allowed[0];
	}

	/** Eventos já contados para a chave na janela atual (0 se a janela venceu). Não consome nada. */
	public int count(String key, Duration window) {
		Window current = windows.get(key);
		if (current == null || clock.millis() - current.startMillis() >= window.toMillis()) {
			return 0;
		}
		return current.count();
	}

	/** Registra um evento (por exemplo, uma falha de login) sem limite; devolve o total na janela. */
	public int record(String key, Duration window) {
		long now = clock.millis();
		long windowMillis = window.toMillis();
		Window updated = windows.compute(key, (k, current) -> current == null || now - current.startMillis() >= windowMillis
				? new Window(now, 1)
				: new Window(current.startMillis(), current.count() + 1));
		housekeeping(now, windowMillis);
		return updated.count();
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
		long remainingMillis = current.startMillis() + window.toMillis() - clock.millis();
		return Math.max(1, (remainingMillis + 999) / 1000);
	}

	int size() {
		return windows.size();
	}

	private void housekeeping(long now, long windowMillis) {
		longestWindowMillis.accumulateAndGet(windowMillis, Math::max);
		if (windows.size() > MAX_KEYS) {
			long longest = longestWindowMillis.get();
			windows.entrySet().removeIf(e -> now - e.getValue().startMillis() >= longest);
			if (windows.size() > MAX_KEYS) {
				windows.clear();
			}
		}
	}
}
