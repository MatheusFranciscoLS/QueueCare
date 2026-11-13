package com.queuecare.scheduling.domain;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * Testes da função pura de sobreposição (não usam banco nem Spring).
 *
 * <p>Os cenários referem-se a docs/cenarios-r2.md. Existente: [10:00, 11:00).
 * Ficam fora desta classe, porque dependem de mais que dois intervalos:
 * D, E e K (status cancelado e outro prestador) e H (concorrência, exige PostgreSQL real, teste T3).
 */
class TimeRangeTest {

	private static Instant at(String hhmm) {
		return Instant.parse("2026-11-16T" + hhmm + ":00Z");
	}

	private static TimeRange range(String from, String to) {
		return new TimeRange(at(from), at(to));
	}

	private final TimeRange existente = range("10:00", "11:00");

	/** Confere nos dois sentidos: a sobreposição é simétrica. */
	private static void assertOverlap(boolean esperado, TimeRange x, TimeRange y) {
		assertAll(
				() -> assertEquals(esperado, x.overlaps(y), "x.overlaps(y)"),
				() -> assertEquals(esperado, y.overlaps(x), "y.overlaps(x)"));
	}

	// --- cenários de docs/cenarios-r2.md ---

	@Test
	void cenarioA_intervaloDentroDeOutroSeSobrepoe() {
		assertOverlap(true, existente, range("10:30", "11:00"));
	}

	@Test
	void cenarioB_intervalosConsecutivosNaoSeSobrepoem() {
		// Começa exatamente quando o outro termina: o fim não conta.
		assertOverlap(false, existente, range("11:00", "12:00"));
	}

	@Test
	void cenarioB_tambemNaoSeSobrepoeQuandoTerminaExatamenteNoInicio() {
		assertOverlap(false, existente, range("09:00", "10:00"));
	}

	@Test
	void cenarioC_sobreposicaoParcialNoInicio() {
		assertOverlap(true, existente, range("09:45", "10:15"));
	}

	@Test
	void sobreposicaoParcialNoFim() {
		assertOverlap(true, existente, range("10:45", "11:30"));
	}

	@Test
	void cenarioF_intervaloIgualSeSobrepoe() {
		assertOverlap(true, existente, range("10:00", "11:00"));
	}

	@Test
	void cenarioG_novoEnvolveOExistente() {
		assertOverlap(true, existente, range("09:30", "11:30"));
	}

	@Test
	void intervalosDistantesNaoSeSobrepoem() {
		assertOverlap(false, existente, range("14:00", "15:00"));
	}

	@Test
	void cenarioI_atravessarAMeiaNoiteNaoMudaARegra() {
		TimeRange noite = new TimeRange(Instant.parse("2026-11-16T23:30:00Z"), Instant.parse("2026-11-17T00:30:00Z"));
		TimeRange depoisDaMeiaNoite = new TimeRange(Instant.parse("2026-11-17T00:15:00Z"), Instant.parse("2026-11-17T01:00:00Z"));
		TimeRange diaSeguinte = new TimeRange(Instant.parse("2026-11-17T00:30:00Z"), Instant.parse("2026-11-17T01:30:00Z"));

		assertOverlap(true, noite, depoisDaMeiaNoite);
		assertOverlap(false, noite, diaSeguinte);
	}

	// --- cenário J: intervalos inválidos ---

	@Test
	void cenarioJ_duracaoZeroEhRejeitada() {
		assertThrows(IllegalArgumentException.class, () -> range("10:00", "10:00"));
	}

	@Test
	void cenarioJ_fimAnteriorAoInicioEhRejeitado() {
		assertThrows(IllegalArgumentException.class, () -> range("11:00", "10:00"));
	}

	@Test
	void instantesNulosSaoRejeitados() {
		assertAll(
				() -> assertThrows(NullPointerException.class, () -> new TimeRange(null, at("11:00"))),
				() -> assertThrows(NullPointerException.class, () -> new TimeRange(at("10:00"), null)));
	}

	@Test
	void overlapsComNuloEhRejeitado() {
		assertThrows(NullPointerException.class, () -> existente.overlaps(null));
	}

	@Test
	void recordComparaPorValor() {
		// Dois intervalos com os mesmos instantes são iguais (record compara por valor).
		assertTrue(existente.equals(range("10:00", "11:00")));
		assertFalse(existente.equals(range("10:00", "11:01")));
	}
}
