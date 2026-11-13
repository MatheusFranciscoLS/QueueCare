package com.queuecare.scheduling.domain;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** Testes da R5 (passado e antecedência mínima), funções puras com o relógio como parâmetro. */
class BookingTimeRulesTest {

	private static final Instant NOW = Instant.parse("2026-11-16T12:00:00Z");

	private static TimeRange startingAt(Instant start) {
		return new TimeRange(start, start.plusSeconds(1800));
	}

	private final BookingTimeRules umaHora = new BookingTimeRules(Duration.ofHours(1));
	private final BookingTimeRules semAntecedencia = new BookingTimeRules(Duration.ZERO);

	@Test
	void inicioNoPassadoEhRejeitado() {
		assertEquals(BookingTimeCheck.IN_THE_PAST, umaHora.check(startingAt(NOW.minusSeconds(3600)), NOW));
	}

	@Test
	void inicioUmNanossegundoNoPassadoEhRejeitadoMesmoSemAntecedencia() {
		assertEquals(BookingTimeCheck.IN_THE_PAST, semAntecedencia.check(startingAt(NOW.minusNanos(1)), NOW));
	}

	@Test
	void inicioExatamenteAgoraSemAntecedenciaEhPermitido() {
		assertEquals(BookingTimeCheck.OK, semAntecedencia.check(startingAt(NOW), NOW));
	}

	@Test
	void inicioExatamenteAgoraComAntecedenciaEhMuitoCedo() {
		assertEquals(BookingTimeCheck.TOO_SOON, umaHora.check(startingAt(NOW), NOW));
	}

	@Test
	void inicioAntesDoLimiteDaAntecedenciaEhMuitoCedo() {
		assertAll(
				() -> assertEquals(BookingTimeCheck.TOO_SOON, umaHora.check(startingAt(NOW.plusSeconds(3599)), NOW)),
				() -> assertEquals(BookingTimeCheck.TOO_SOON, umaHora.check(startingAt(NOW.plusSeconds(3600).minusNanos(1)), NOW)));
	}

	@Test
	void inicioExatamenteNoLimiteDaAntecedenciaEhPermitido() {
		assertEquals(BookingTimeCheck.OK, umaHora.check(startingAt(NOW.plusSeconds(3600)), NOW));
	}

	@Test
	void inicioDepoisDoLimiteEhPermitido() {
		assertAll(
				() -> assertEquals(BookingTimeCheck.OK, umaHora.check(startingAt(NOW.plusSeconds(3600).plusNanos(1)), NOW)),
				() -> assertEquals(BookingTimeCheck.OK, umaHora.check(startingAt(NOW.plusSeconds(86400 * 30L)), NOW)));
	}

	@Test
	void antecedenciaEnormeNaoEstouraEApenasRejeita() {
		BookingTimeRules enorme = new BookingTimeRules(Duration.ofSeconds(Long.MAX_VALUE));

		assertAll(
				() -> assertEquals(BookingTimeCheck.TOO_SOON, enorme.check(startingAt(NOW.plusSeconds(86400)), NOW)),
				() -> assertEquals(BookingTimeCheck.IN_THE_PAST, enorme.check(startingAt(NOW.minusSeconds(1)), NOW)));
	}

	@Test
	void extremosDeInstantNaoEstouram() {
		TimeRange noFimDosTempos = new TimeRange(Instant.MAX.minusSeconds(10), Instant.MAX);
		TimeRange noInicioDosTempos = new TimeRange(Instant.MIN, Instant.MIN.plusSeconds(10));

		assertAll(
				() -> assertEquals(BookingTimeCheck.OK, semAntecedencia.check(noFimDosTempos, Instant.MIN)),
				() -> assertEquals(BookingTimeCheck.IN_THE_PAST, semAntecedencia.check(noInicioDosTempos, Instant.MAX)));
	}

	@Test
	void antecedenciaInvalidaEhRejeitada() {
		assertAll(
				() -> assertThrows(IllegalArgumentException.class, () -> new BookingTimeRules(Duration.ofMinutes(-1))),
				() -> assertThrows(NullPointerException.class, () -> new BookingTimeRules(null)),
				() -> assertEquals(Duration.ZERO, new BookingTimeRules(Duration.ZERO).minimumNotice()));
	}

	@Test
	void argumentosNulosDoCheckSaoRejeitados() {
		assertAll(
				() -> assertThrows(NullPointerException.class, () -> umaHora.check(null, NOW)),
				() -> assertThrows(NullPointerException.class, () -> umaHora.check(startingAt(NOW), null)));
	}
}
