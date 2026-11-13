package com.queuecare.scheduling.domain;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * Testes da função pura de sobreposição (não usam banco nem Spring).
 * A concorrência (cenário H) NÃO se prova aqui: depende de PostgreSQL real (teste T3).
 */
class TimeRangeTest {

	private static Instant at(String hhmm) {
		return Instant.parse("2026-11-16T" + hhmm + ":00Z");
	}

	/** Cenário A de docs/cenarios-r2.md: 60 min às 10:00 existente, novo de 30 min às 10:30. */
	@Test
	void intervaloDentroDeOutroSeSobrepoe() {
		TimeRange existente = new TimeRange(at("10:00"), at("11:00"));
		TimeRange novo = new TimeRange(at("10:30"), at("11:00"));

		assertTrue(existente.overlaps(novo));
	}

	// TODO(matheus): escreva os demais testes sozinho, um por cenário (B a G, I a K) e mais 3 cenários inventados por você.
	// Escreva o teste ANTES de ajustar a implementação e veja ele falhar primeiro.
}
