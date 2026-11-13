package com.queuecare.scheduling.domain;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** R3 pura: prazo de cancelamento. */
class CancellationPolicyTest {

	private static final Instant NOW = Instant.parse("2026-11-16T12:00:00Z");
	private final CancellationPolicy duasHoras = new CancellationPolicy(Duration.ofHours(2));

	@Test
	void comMaisAntecedenciaQueOPrazoPodeCancelar() {
		assertTrue(duasHoras.allows(NOW.plusSeconds(3 * 3600), NOW));
	}

	@Test
	void exatamenteNoLimiteDoPrazoPodeCancelar() {
		assertTrue(duasHoras.allows(NOW.plusSeconds(2 * 3600), NOW));
	}

	@Test
	void umNanossegundoDentroDoPrazoNaoPodeCancelar() {
		assertFalse(duasHoras.allows(NOW.plusSeconds(2 * 3600).minusNanos(1), NOW));
	}

	@Test
	void jaIniciadoOuPassadoNaoPodeCancelar() {
		assertAll(
				() -> assertFalse(duasHoras.allows(NOW, NOW)),
				() -> assertFalse(duasHoras.allows(NOW.minusSeconds(1), NOW)),
				() -> assertFalse(new CancellationPolicy(Duration.ZERO).allows(NOW.minusNanos(1), NOW)));
	}

	@Test
	void prazoZeroPermiteCancelarAteOInstanteDoInicio() {
		CancellationPolicy semPrazo = new CancellationPolicy(Duration.ZERO);

		assertAll(
				() -> assertTrue(semPrazo.allows(NOW, NOW)),
				() -> assertTrue(semPrazo.allows(NOW.plusNanos(1), NOW)));
	}

	@Test
	void prazoEnormeNaoEstoura() {
		assertFalse(new CancellationPolicy(Duration.ofSeconds(Long.MAX_VALUE)).allows(NOW.plusSeconds(86400), NOW));
	}

	@Test
	void argumentosInvalidosSaoRejeitados() {
		assertAll(
				() -> assertThrows(IllegalArgumentException.class, () -> new CancellationPolicy(Duration.ofMinutes(-1))),
				() -> assertThrows(NullPointerException.class, () -> new CancellationPolicy(null)),
				() -> assertThrows(NullPointerException.class, () -> duasHoras.allows(null, NOW)),
				() -> assertThrows(NullPointerException.class, () -> duasHoras.allows(NOW, null)));
	}
}
