package com.queuecare.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Limitador de janela fixa, puro, com relógio controlado pelo teste. */
class RateLimiterTest {

	/** Relógio que o teste avança à mão. */
	private static final class MutableClock extends Clock {
		private volatile Instant now = Instant.parse("2026-11-16T12:00:00Z");

		void advance(Duration d) {
			now = now.plus(d);
		}

		@Override
		public ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return this;
		}

		@Override
		public Instant instant() {
			return now;
		}
	}

	private static final Duration MINUTE = Duration.ofMinutes(1);
	private final MutableClock clock = new MutableClock();
	private final RateLimiter limiter = new RateLimiter(clock);

	@Test
	void permiteAteOLimiteENegaDepois() {
		assertTrue(limiter.tryAcquire("k", 3, MINUTE));
		assertTrue(limiter.tryAcquire("k", 3, MINUTE));
		assertTrue(limiter.tryAcquire("k", 3, MINUTE));
		assertFalse(limiter.tryAcquire("k", 3, MINUTE));
		assertFalse(limiter.tryAcquire("k", 3, MINUTE));
	}

	@Test
	void aJanelaReiniciaDepoisDoTempo() {
		for (int i = 0; i < 3; i++) {
			limiter.tryAcquire("k", 3, MINUTE);
		}
		assertFalse(limiter.tryAcquire("k", 3, MINUTE));

		clock.advance(Duration.ofSeconds(59));
		assertFalse(limiter.tryAcquire("k", 3, MINUTE), "ainda dentro da janela");
		clock.advance(Duration.ofSeconds(1));
		assertTrue(limiter.tryAcquire("k", 3, MINUTE), "janela nova");
	}

	@Test
	void chavesSaoIndependentes() {
		assertTrue(limiter.tryAcquire("a", 1, MINUTE));
		assertFalse(limiter.tryAcquire("a", 1, MINUTE));
		assertTrue(limiter.tryAcquire("b", 1, MINUTE));
	}

	@Test
	void retryAfterConsideraOTempoRestanteComMinimoDeUmSegundo() {
		limiter.tryAcquire("k", 1, MINUTE);
		assertEquals(60, limiter.retryAfterSeconds("k", MINUTE));
		clock.advance(Duration.ofSeconds(45));
		assertEquals(15, limiter.retryAfterSeconds("k", MINUTE));
		clock.advance(Duration.ofMillis(14_500));
		assertEquals(1, limiter.retryAfterSeconds("k", MINUTE), "nunca menos de 1 s");
		assertEquals(1, limiter.retryAfterSeconds("inexistente", MINUTE));
	}

	@Test
	void clearZeraUmaChaveEClearAllZeraTudo() {
		limiter.tryAcquire("a", 1, MINUTE);
		limiter.tryAcquire("b", 1, MINUTE);

		limiter.clear("a");
		assertTrue(limiter.tryAcquire("a", 1, MINUTE), "a chave limpa recomeça");
		assertFalse(limiter.tryAcquire("b", 1, MINUTE), "a outra continua contando");

		limiter.clearAll();
		assertTrue(limiter.tryAcquire("b", 1, MINUTE));
		assertEquals(1, limiter.size());
	}

	@Test
	void limiteZeroNegaSempre() {
		assertFalse(limiter.tryAcquire("k", 0, MINUTE));
	}

	@Test
	void aMemoriaEhLimitadaMesmoComMuitasChaves() {
		RateLimiter small = new RateLimiter(clock, 1_000);
		for (int i = 0; i <= 5_000; i++) {
			small.tryAcquire("chave-" + i, 5, MINUTE);
		}

		assertTrue(small.size() <= 1_000, "tamanho: " + small.size());
	}

	@Test
	void chavesVencidasSaoDescartadasPrimeiro() {
		RateLimiter small = new RateLimiter(clock, 1_000);
		for (int i = 0; i < 1_000; i++) {
			small.tryAcquire("velha-" + i, 5, MINUTE);
		}
		clock.advance(Duration.ofMinutes(2));
		small.tryAcquire("nova-1", 5, MINUTE);
		small.tryAcquire("nova-2", 5, MINUTE); // passa do limite de chaves: descarta as vencidas

		assertTrue(small.size() < 10, "sobraram só as recentes: " + small.size());
		assertTrue(small.tryAcquire("nova-1", 2, MINUTE));
		assertFalse(small.tryAcquire("nova-1", 2, MINUTE), "as recentes continuam contando");
	}

	/** O ponto do ajuste: inundar o mapa com chaves novas NÃO pode apagar um bloqueio em andamento. */
	@Test
	void inundarComChavesNovasNaoApagaOsBloqueios() {
		RateLimiter small = new RateLimiter(clock, 1_000);
		Duration window = Duration.ofMinutes(15);
		for (int i = 0; i < 5; i++) {
			small.tryAcquire("bloqueada", 5, window);
		}
		assertFalse(small.tryAcquire("bloqueada", 5, window), "bloqueada");

		for (int i = 0; i < 20_000; i++) {
			small.tryAcquire("inundacao-" + i, 5, window); // todas com janela viva e contagem 1
		}

		assertTrue(small.size() <= 1_000, "memória continua limitada: " + small.size());
		assertFalse(small.tryAcquire("bloqueada", 5, window), "o bloqueio sobreviveu à inundação");
	}

	@Test
	void entradaVenceNaSuaPropriaJanelaMesmoQuandoOutraUsaJanelaMaior() {
		RateLimiter small = new RateLimiter(clock, 100);
		small.tryAcquire("longa", 5, Duration.ofHours(1));
		for (int i = 0; i < 98; i++) {
			small.tryAcquire("curta-" + i, 5, MINUTE);
		}
		clock.advance(Duration.ofMinutes(2));

		small.tryAcquire("gatilho-1", 5, MINUTE);
		small.tryAcquire("gatilho-2", 5, MINUTE); // passa de 100 chaves: as "curta-*" já venceram e saem; a "longa" fica

		assertEquals(3, small.size(), "só a longa (ainda viva) e os dois gatilhos");
		assertTrue(small.tryAcquire("longa", 2, Duration.ofHours(1)));
		assertFalse(small.tryAcquire("longa", 2, Duration.ofHours(1)), "a longa seguiu contando");
	}

	/** Atomicidade: com muitas threads ao mesmo tempo, no máximo "limit" passam. */
	@Test
	void tentativasSimultaneasNuncaPassamDoLimite() throws Exception {
		int threads = 64;
		int limit = 5;
		ExecutorService pool = Executors.newFixedThreadPool(threads);
		try {
			CyclicBarrier barrier = new CyclicBarrier(threads);
			AtomicInteger allowed = new AtomicInteger();
			List<Future<?>> futures = new ArrayList<>();
			for (int i = 0; i < threads; i++) {
				futures.add(pool.submit(() -> {
					barrier.await(10, TimeUnit.SECONDS);
					if (limiter.tryAcquire("quente", limit, MINUTE)) {
						allowed.incrementAndGet();
					}
					return null;
				}));
			}
			for (Future<?> f : futures) {
				f.get(20, TimeUnit.SECONDS);
			}

			assertEquals(limit, allowed.get());
		} finally {
			pool.shutdownNow();
		}
	}
}
