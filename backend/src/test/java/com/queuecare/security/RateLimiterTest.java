package com.queuecare.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/** Limitador de janela fixa, puro, com relógio controlado pelo teste. */
class RateLimiterTest {

	/** Relógio que o teste avança à mão. */
	private static final class MutableClock extends Clock {
		private Instant now = Instant.parse("2026-11-16T12:00:00Z");

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
	void contagemDeFalhasRegistraConsultaELimpa() {
		Duration window = Duration.ofMinutes(15);
		assertEquals(0, limiter.count("f", window));
		assertEquals(1, limiter.record("f", window));
		assertEquals(2, limiter.record("f", window));
		assertEquals(2, limiter.count("f", window));

		clock.advance(Duration.ofMinutes(15));
		assertEquals(0, limiter.count("f", window), "falhas antigas expiram");
		assertEquals(1, limiter.record("f", window), "nova janela recomeça do 1");

		limiter.clear("f");
		assertEquals(0, limiter.count("f", window));
	}

	@Test
	void limiteZeroNegaSempre() {
		assertFalse(limiter.tryAcquire("k", 0, MINUTE));
	}

	@Test
	void aMemoriaEhLimitadaMesmoComMuitasChaves() {
		for (int i = 0; i <= RateLimiter.MAX_KEYS + 10; i++) {
			limiter.tryAcquire("chave-" + i, 5, MINUTE);
		}

		assertTrue(limiter.size() <= RateLimiter.MAX_KEYS, "tamanho: " + limiter.size());
	}

	@Test
	void chavesVencidasSaoDescartadasAntesDeZerarTudo() {
		for (int i = 0; i < RateLimiter.MAX_KEYS; i++) {
			limiter.tryAcquire("velha-" + i, 5, MINUTE);
		}
		clock.advance(Duration.ofMinutes(2));
		limiter.tryAcquire("nova-1", 5, MINUTE);
		limiter.tryAcquire("nova-2", 5, MINUTE); // passa do limite de chaves: descarta as vencidas

		assertTrue(limiter.size() < 100, "sobraram só as recentes: " + limiter.size());
		assertFalse(limiter.tryAcquire("nova-1", 1, MINUTE) && limiter.tryAcquire("nova-1", 1, MINUTE), "as recentes continuam contando");
	}
}
