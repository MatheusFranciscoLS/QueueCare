package com.queuecare.scheduling.domain;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.Test;

/**
 * Testes da R5 (horário local -> instante com horário de verão).
 * Em 2026: Nova York avança o relógio em 08/03 (02:00 vira 03:00) e volta em 01/11 (02:00 vira 01:00);
 * Lord Howe (Austrália) tem transições de 30 minutos em 04/10 (02:00 vira 02:30) e 05/04 (02:00 vira 01:30).
 */
class LocalTimeResolverTest {

	private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");
	private static final ZoneId SAO_PAULO = ZoneId.of("America/Sao_Paulo");
	private static final ZoneId LORD_HOWE = ZoneId.of("Australia/Lord_Howe");

	private static LocalDateTime at(int year, int month, int day, int hour, int minute) {
		return LocalDateTime.of(year, month, day, hour, minute);
	}

	private static ResolvedLocalTime resolve(ZoneId zone, LocalDateTime local) {
		return new LocalTimeResolver(zone).resolve(local);
	}

	// --- horário comum ---

	@Test
	void horarioComumEmSaoPauloVirouInstanteUtc() {
		ResolvedLocalTime result = resolve(SAO_PAULO, at(2026, 11, 16, 10, 0));

		assertEquals(new ResolvedLocalTime.Resolved(Instant.parse("2026-11-16T13:00:00Z")), result);
	}

	@Test
	void meiaNoiteLocalEhValida() {
		ResolvedLocalTime result = resolve(SAO_PAULO, at(2026, 11, 16, 0, 0));

		assertEquals(new ResolvedLocalTime.Resolved(Instant.parse("2026-11-16T03:00:00Z")), result);
	}

	@Test
	void horarioComumEmNovaYorkForaDasTransicoes() {
		assertAll(
				() -> assertEquals(new ResolvedLocalTime.Resolved(Instant.parse("2026-07-15T14:00:00Z")), resolve(NEW_YORK, at(2026, 7, 15, 10, 0))),
				() -> assertEquals(new ResolvedLocalTime.Resolved(Instant.parse("2026-12-15T15:00:00Z")), resolve(NEW_YORK, at(2026, 12, 15, 10, 0))));
	}

	// --- Nova York, primavera: lacuna 02:00-03:00 em 08/03/2026 ---

	@Test
	void primaveraUltimoInstanteAntesDaLacunaEhValido() {
		LocalDateTime local = LocalDateTime.of(2026, 3, 8, 1, 59, 59, 999_999_999);

		ResolvedLocalTime result = resolve(NEW_YORK, local);

		assertEquals(new ResolvedLocalTime.Resolved(ZonedDateTime.of(local, NEW_YORK).toInstant()), result);
	}

	@Test
	void primaveraInicioDaLacunaNaoExiste() {
		ResolvedLocalTime result = resolve(NEW_YORK, at(2026, 3, 8, 2, 0));

		assertEquals(new ResolvedLocalTime.Nonexistent(at(2026, 3, 8, 2, 0), at(2026, 3, 8, 3, 0)), result);
	}

	@Test
	void primaveraMeioDaLacunaNaoExisteEProximoValidoEhAsTres() {
		ResolvedLocalTime result = resolve(NEW_YORK, at(2026, 3, 8, 2, 30));

		assertEquals(new ResolvedLocalTime.Nonexistent(at(2026, 3, 8, 2, 30), at(2026, 3, 8, 3, 0)), result);
	}

	@Test
	void primaveraFimDaLacunaTresHorasEhValido() {
		ResolvedLocalTime result = resolve(NEW_YORK, at(2026, 3, 8, 3, 0));

		// 03:00 EDT (UTC-4) = 07:00 UTC
		assertEquals(new ResolvedLocalTime.Resolved(Instant.parse("2026-03-08T07:00:00Z")), result);
	}

	// --- Nova York, outono: sobreposição 01:00-02:00 em 01/11/2026 ---

	@Test
	void outonoAntesDaSobreposicaoEhValido() {
		LocalDateTime local = LocalDateTime.of(2026, 11, 1, 0, 59, 59, 999_999_999);

		assertInstanceOf(ResolvedLocalTime.Resolved.class, resolve(NEW_YORK, local));
	}

	@Test
	void outonoInicioDaSobreposicaoEhAmbiguo() {
		ResolvedLocalTime result = resolve(NEW_YORK, at(2026, 11, 1, 1, 0));

		// 01:00 EDT (UTC-4) = 05:00 UTC; 01:00 EST (UTC-5) = 06:00 UTC
		assertEquals(new ResolvedLocalTime.Ambiguous(at(2026, 11, 1, 1, 0), Instant.parse("2026-11-01T05:00:00Z"), Instant.parse("2026-11-01T06:00:00Z")), result);
	}

	@Test
	void outonoMeioDaSobreposicaoEhAmbiguoComOsInstantesOrdenados() {
		ResolvedLocalTime result = resolve(NEW_YORK, at(2026, 11, 1, 1, 30));

		ResolvedLocalTime.Ambiguous ambiguous = assertInstanceOf(ResolvedLocalTime.Ambiguous.class, result);
		assertAll(
				() -> assertEquals(Instant.parse("2026-11-01T05:30:00Z"), ambiguous.earlier()),
				() -> assertEquals(Instant.parse("2026-11-01T06:30:00Z"), ambiguous.later()),
				() -> assertTrue(ambiguous.earlier().isBefore(ambiguous.later())),
				() -> assertEquals(Duration.ofHours(1), Duration.between(ambiguous.earlier(), ambiguous.later())));
	}

	@Test
	void outonoUltimoInstanteDaSobreposicaoAindaEhAmbiguo() {
		ResolvedLocalTime result = resolve(NEW_YORK, LocalDateTime.of(2026, 11, 1, 1, 59, 59, 999_999_999));

		assertInstanceOf(ResolvedLocalTime.Ambiguous.class, result);
	}

	@Test
	void outonoDuasHorasEhValidoEUnico() {
		ResolvedLocalTime result = resolve(NEW_YORK, at(2026, 11, 1, 2, 0));

		// 02:00 EST (UTC-5) = 07:00 UTC
		assertEquals(new ResolvedLocalTime.Resolved(Instant.parse("2026-11-01T07:00:00Z")), result);
	}

	// --- Lord Howe: transições de 30 minutos ---

	@Test
	void lordHoweLacunaDeTrintaMinutos() {
		ResolvedLocalTime naLacuna = resolve(LORD_HOWE, at(2026, 10, 4, 2, 15));
		ResolvedLocalTime fimDaLacuna = resolve(LORD_HOWE, at(2026, 10, 4, 2, 30));

		assertAll(
				() -> assertEquals(new ResolvedLocalTime.Nonexistent(at(2026, 10, 4, 2, 15), at(2026, 10, 4, 2, 30)), naLacuna),
				() -> assertInstanceOf(ResolvedLocalTime.Resolved.class, fimDaLacuna));
	}

	@Test
	void lordHoweSobreposicaoDeTrintaMinutos() {
		ResolvedLocalTime result = resolve(LORD_HOWE, at(2026, 4, 5, 1, 45));
		ResolvedLocalTime depois = resolve(LORD_HOWE, at(2026, 4, 5, 2, 0));

		ResolvedLocalTime.Ambiguous ambiguous = assertInstanceOf(ResolvedLocalTime.Ambiguous.class, result);
		assertAll(
				() -> assertEquals(Duration.ofMinutes(30), Duration.between(ambiguous.earlier(), ambiguous.later())),
				() -> assertInstanceOf(ResolvedLocalTime.Resolved.class, depois));
	}

	// --- São Paulo, datas históricas com horário de verão ---

	@Test
	void saoPauloHistoricoLacunaEm2018() {
		// Em 04/11/2018 os relógios passaram de 00:00 para 01:00.
		ResolvedLocalTime result = resolve(SAO_PAULO, at(2018, 11, 4, 0, 30));

		assertEquals(new ResolvedLocalTime.Nonexistent(at(2018, 11, 4, 0, 30), at(2018, 11, 4, 1, 0)), result);
	}

	@Test
	void saoPauloHistoricoSobreposicaoEm2019() {
		// Em 17/02/2019 os relógios voltaram de 00:00 para 23:00 do dia 16.
		ResolvedLocalTime result = resolve(SAO_PAULO, at(2019, 2, 16, 23, 30));

		assertInstanceOf(ResolvedLocalTime.Ambiguous.class, result);
	}

	@Test
	void saoPauloHoje() {
		// Sem horário de verão desde 2019: nenhuma data de 2026 é inexistente ou ambígua.
		assertInstanceOf(ResolvedLocalTime.Resolved.class, resolve(SAO_PAULO, at(2026, 11, 1, 0, 30)));
		assertInstanceOf(ResolvedLocalTime.Resolved.class, resolve(SAO_PAULO, at(2026, 2, 20, 23, 30)));
	}

	// --- validação ---

	@Test
	void argumentosNulosSaoRejeitados() {
		assertAll(
				() -> assertThrows(NullPointerException.class, () -> new LocalTimeResolver(null)),
				() -> assertThrows(NullPointerException.class, () -> new LocalTimeResolver(SAO_PAULO).resolve(null)),
				() -> assertEquals(SAO_PAULO, new LocalTimeResolver(SAO_PAULO).zone()));
	}

	@Test
	void ambiguousExigeEarlierAnteriorALater() {
		Instant t = Instant.parse("2026-11-01T05:00:00Z");

		assertThrows(IllegalArgumentException.class, () -> new ResolvedLocalTime.Ambiguous(at(2026, 11, 1, 1, 0), t, t));
	}
}
