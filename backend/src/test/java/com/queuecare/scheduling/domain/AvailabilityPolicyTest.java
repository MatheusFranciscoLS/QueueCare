package com.queuecare.scheduling.domain;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Testes da regra R1 (disponibilidade), funções puras, sem banco nem Spring.
 * O teste T2 de docs/regras.md é {@link #t2_servicoDe60MinutosAs1730ComExpedienteAte18hEhRejeitado()}.
 *
 * <p>2026-11-16 é segunda-feira, 2026-11-17 é terça-feira.
 */
class AvailabilityPolicyTest {

	private static final ZoneId SAO_PAULO = ZoneId.of("America/Sao_Paulo");
	private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");

	private static AvailabilityWindow window(DayOfWeek day, String from, String to) {
		return new AvailabilityWindow(day, LocalTime.parse(from), LocalTime.parse(to));
	}

	private static Instant local(ZoneId zone, int day, int hour, int minute) {
		return ZonedDateTime.of(2026, 11, day, hour, minute, 0, 0, zone).toInstant();
	}

	/** Intervalo em segunda-feira 16/11/2026, no fuso informado. */
	private static TimeRange monday(ZoneId zone, int fromH, int fromM, int toH, int toM) {
		return new TimeRange(local(zone, 16, fromH, fromM), local(zone, 16, toH, toM));
	}

	/** Prestador em São Paulo: segunda 08:00-12:00 e 14:00-18:00. */
	private final AvailabilityPolicy politica = new AvailabilityPolicy(SAO_PAULO, List.of(
			window(DayOfWeek.MONDAY, "08:00", "12:00"),
			window(DayOfWeek.MONDAY, "14:00", "18:00")));

	// --- T2 e limites do expediente ---

	@Test
	void t2_servicoDe60MinutosAs1730ComExpedienteAte18hEhRejeitado() {
		assertFalse(politica.allows(monday(SAO_PAULO, 17, 30, 18, 30)));
	}

	@Test
	void t1_agendamentoDentroDoExpedienteEhPermitido() {
		assertTrue(politica.allows(monday(SAO_PAULO, 9, 0, 10, 0)));
	}

	@Test
	void comecaExatamenteNoInicioDaJanelaEhPermitido() {
		assertTrue(politica.allows(monday(SAO_PAULO, 8, 0, 9, 0)));
	}

	@Test
	void terminaExatamenteNoFimDaJanelaEhPermitido() {
		assertTrue(politica.allows(monday(SAO_PAULO, 17, 0, 18, 0)));
	}

	@Test
	void ocupaAJanelaInteiraEhPermitido() {
		assertTrue(politica.allows(monday(SAO_PAULO, 8, 0, 12, 0)));
	}

	@Test
	void comecaAntesDaJanelaEhRejeitado() {
		assertFalse(politica.allows(monday(SAO_PAULO, 7, 30, 8, 30)));
	}

	@Test
	void comecaDentroEMTerminaDepoisDaJanelaEhRejeitado() {
		assertFalse(politica.allows(monday(SAO_PAULO, 17, 30, 18, 1)));
	}

	// --- dois trechos no mesmo dia ---

	@Test
	void dentroDoSegundoTrechoEhPermitido() {
		assertTrue(politica.allows(monday(SAO_PAULO, 15, 0, 16, 0)));
	}

	@Test
	void noIntervaloDoAlmocoEhRejeitado() {
		assertFalse(politica.allows(monday(SAO_PAULO, 12, 0, 13, 0)));
	}

	@Test
	void atravessandoALacunaEntreOsTrechosEhRejeitado() {
		assertFalse(politica.allows(monday(SAO_PAULO, 11, 30, 14, 30)));
	}

	// --- janelas coladas e lacunas ---

	@Test
	void janelasColadasFormamUmBlocoContinuo() {
		AvailabilityPolicy continuo = new AvailabilityPolicy(SAO_PAULO, List.of(
				window(DayOfWeek.MONDAY, "08:00", "12:00"),
				window(DayOfWeek.MONDAY, "12:00", "18:00")));

		assertTrue(continuo.allows(monday(SAO_PAULO, 11, 30, 12, 30)));
	}

	@Test
	void janelasColadasFuncionamMesmoForaDeOrdem() {
		AvailabilityPolicy continuo = new AvailabilityPolicy(SAO_PAULO, List.of(
				window(DayOfWeek.MONDAY, "12:00", "18:00"),
				window(DayOfWeek.MONDAY, "08:00", "12:00")));

		assertTrue(continuo.allows(monday(SAO_PAULO, 11, 30, 12, 30)));
	}

	@Test
	void lacunaDeUmMinutoInterrompeOBloco() {
		AvailabilityPolicy comLacuna = new AvailabilityPolicy(SAO_PAULO, List.of(
				window(DayOfWeek.MONDAY, "08:00", "11:59"),
				window(DayOfWeek.MONDAY, "12:00", "18:00")));

		assertFalse(comLacuna.allows(monday(SAO_PAULO, 11, 30, 12, 30)));
	}

	// --- dia da semana e meia-noite ---

	@Test
	void diaDaSemanaSemJanelaEhRejeitado() {
		TimeRange terca = new TimeRange(local(SAO_PAULO, 17, 9, 0), local(SAO_PAULO, 17, 10, 0));

		assertFalse(politica.allows(terca));
	}

	@Test
	void listaVaziaEhValidaERejeitaTudo() {
		AvailabilityPolicy semExpediente = new AvailabilityPolicy(SAO_PAULO, List.of());

		assertFalse(semExpediente.allows(monday(SAO_PAULO, 9, 0, 10, 0)));
	}

	@Test
	void atravessarAMeiaNoiteLocalEhRejeitado() {
		AvailabilityPolicy noite = new AvailabilityPolicy(SAO_PAULO, List.of(
				window(DayOfWeek.MONDAY, "20:00", "23:59"),
				window(DayOfWeek.TUESDAY, "00:00", "06:00")));
		TimeRange atravessa = new TimeRange(local(SAO_PAULO, 16, 23, 30), local(SAO_PAULO, 17, 0, 30));

		assertFalse(noite.allows(atravessa));
	}

	@Test
	void terminarExatamenteNaMeiaNoiteLocalEhRejeitadoPorContarComoDiaSeguinte() {
		AvailabilityPolicy noite = new AvailabilityPolicy(SAO_PAULO, List.of(window(DayOfWeek.MONDAY, "20:00", "23:59")));
		TimeRange ateMeiaNoite = new TimeRange(local(SAO_PAULO, 16, 22, 0), local(SAO_PAULO, 17, 0, 0));

		assertFalse(noite.allows(ateMeiaNoite));
	}

	// --- fuso do prestador ---

	@Test
	void usaOFusoDoPrestadorENaoUtc() {
		// 22:00-22:30 de segunda em São Paulo é 01:00-01:30 de TERÇA em UTC.
		AvailabilityPolicy noite = new AvailabilityPolicy(SAO_PAULO, List.of(window(DayOfWeek.MONDAY, "20:00", "23:00")));
		TimeRange range = monday(SAO_PAULO, 22, 0, 22, 30);

		assertAll(
				() -> assertEquals(DayOfWeek.TUESDAY, range.startsAt().atZone(ZoneId.of("UTC")).getDayOfWeek()),
				() -> assertTrue(noite.allows(range)));
	}

	@Test
	void mesmoInstanteEmOutroFusoCaiEmOutroDia() {
		// Segunda 01:00 UTC ainda é domingo à noite em Nova York.
		Instant instante = Instant.parse("2026-11-16T01:00:00Z");
		TimeRange range = new TimeRange(instante, instante.plusSeconds(1800));
		AvailabilityPolicy domingoNoite = new AvailabilityPolicy(NEW_YORK, List.of(window(DayOfWeek.SUNDAY, "19:00", "23:00")));
		AvailabilityPolicy segundaMadrugada = new AvailabilityPolicy(NEW_YORK, List.of(window(DayOfWeek.MONDAY, "00:00", "06:00")));

		assertAll(
				() -> assertTrue(domingoNoite.allows(range)),
				() -> assertFalse(segundaMadrugada.allows(range)));
	}

	// --- horário de verão (America/New_York) ---

	@Test
	void primaveraIntervaloQueAtravessaATransicaoUsaOHorarioLocalResultante() {
		// 08/03/2026: às 02:00 os relógios pulam para 03:00. Das 01:30 às 03:30 locais são só 60 minutos reais.
		ZonedDateTime inicio = ZonedDateTime.of(2026, 3, 8, 1, 30, 0, 0, NEW_YORK);
		ZonedDateTime fim = ZonedDateTime.of(2026, 3, 8, 3, 30, 0, 0, NEW_YORK);
		TimeRange range = new TimeRange(inicio.toInstant(), fim.toInstant());
		AvailabilityPolicy domingo = new AvailabilityPolicy(NEW_YORK, List.of(window(DayOfWeek.SUNDAY, "01:00", "06:00")));
		AvailabilityPolicy domingoCurto = new AvailabilityPolicy(NEW_YORK, List.of(window(DayOfWeek.SUNDAY, "01:00", "03:00")));

		assertAll(
				() -> assertEquals(3600, java.time.Duration.between(inicio, fim).getSeconds()),
				() -> assertTrue(domingo.allows(range)),
				() -> assertFalse(domingoCurto.allows(range)));
	}

	@Test
	void outonoIntervaloQueCruzaAVoltaDoHorarioDeVeraoEhRejeitadoPorSeguranca() {
		// 01/11/2026: 01:30 acontece duas vezes (EDT e depois EST). Do primeiro 01:30 ao segundo 01:30 são 60 minutos reais,
		// mas o relógio local termina igual ao início. O sistema rejeita em vez de arriscar.
		ZonedDateTime primeiro = ZonedDateTime.of(2026, 11, 1, 1, 30, 0, 0, NEW_YORK).withEarlierOffsetAtOverlap();
		ZonedDateTime segundo = primeiro.withLaterOffsetAtOverlap();
		TimeRange range = new TimeRange(primeiro.toInstant(), segundo.toInstant());
		AvailabilityPolicy domingo = new AvailabilityPolicy(NEW_YORK, List.of(window(DayOfWeek.SUNDAY, "00:00", "06:00")));

		assertAll(
				() -> assertEquals(3600, java.time.Duration.between(primeiro, segundo).getSeconds()),
				() -> assertFalse(domingo.allows(range)));
	}

	@Test
	void outonoIntervaloComHorariosLocaisCrescentesQueCruzaAVoltaEhRejeitado() {
		// Caso apontado na revisão: janela 01:45-02:15; agendamento 01:50 EDT até 02:10 EST.
		// O relógio local vai de 01:50 a 02:10 (crescente), mas em tempo real são 80 minutos:
		// 01:50 EDT -> (volta) 01:00 EST -> 02:10 EST, passando por 01:00-01:45 EST, fora da janela.
		ZonedDateTime inicio = ZonedDateTime.of(2026, 11, 1, 1, 50, 0, 0, NEW_YORK).withEarlierOffsetAtOverlap();
		ZonedDateTime fim = ZonedDateTime.of(2026, 11, 1, 2, 10, 0, 0, NEW_YORK);
		TimeRange range = new TimeRange(inicio.toInstant(), fim.toInstant());
		AvailabilityPolicy janelaEstreita = new AvailabilityPolicy(NEW_YORK, List.of(window(DayOfWeek.SUNDAY, "01:45", "02:15")));

		assertAll(
				() -> assertEquals(80, java.time.Duration.between(inicio, fim).toMinutes()),
				() -> assertTrue(inicio.toLocalTime().isBefore(fim.toLocalTime())),
				() -> assertFalse(janelaEstreita.allows(range)));
	}

	@Test
	void outonoIntervaloDentroDaPrimeiraPassagemDaHoraRepetidaEhPermitido() {
		ZonedDateTime inicio = ZonedDateTime.of(2026, 11, 1, 1, 10, 0, 0, NEW_YORK).withEarlierOffsetAtOverlap();
		ZonedDateTime fim = ZonedDateTime.of(2026, 11, 1, 1, 50, 0, 0, NEW_YORK).withEarlierOffsetAtOverlap();
		TimeRange range = new TimeRange(inicio.toInstant(), fim.toInstant());
		AvailabilityPolicy domingo = new AvailabilityPolicy(NEW_YORK, List.of(window(DayOfWeek.SUNDAY, "01:00", "02:00")));

		assertTrue(domingo.allows(range));
	}

	@Test
	void outonoIntervaloDentroDaSegundaPassagemDaHoraRepetidaEhPermitido() {
		ZonedDateTime inicio = ZonedDateTime.of(2026, 11, 1, 1, 10, 0, 0, NEW_YORK).withLaterOffsetAtOverlap();
		ZonedDateTime fim = ZonedDateTime.of(2026, 11, 1, 1, 50, 0, 0, NEW_YORK).withLaterOffsetAtOverlap();
		TimeRange range = new TimeRange(inicio.toInstant(), fim.toInstant());
		AvailabilityPolicy domingo = new AvailabilityPolicy(NEW_YORK, List.of(window(DayOfWeek.SUNDAY, "01:00", "02:00")));

		assertTrue(domingo.allows(range));
	}

	@Test
	void primaveraIntervaloQueAtravessaATransicaoMasNaoCabeNaJanelaEhRejeitado() {
		// 01:30 a 03:30 locais em 08/03/2026; a janela só vai até 03:00.
		TimeRange range = new TimeRange(
				ZonedDateTime.of(2026, 3, 8, 1, 30, 0, 0, NEW_YORK).toInstant(),
				ZonedDateTime.of(2026, 3, 8, 3, 30, 0, 0, NEW_YORK).toInstant());
		AvailabilityPolicy janelaCurta = new AvailabilityPolicy(NEW_YORK, List.of(window(DayOfWeek.SUNDAY, "01:00", "03:00")));

		assertFalse(janelaCurta.allows(range));
	}

	@Test
	void intervaloQueTerminaExatamenteNaVoltaDoRelogioNaoCruzaATransicao() {
		// A volta acontece às 06:00 UTC (02:00 EDT -> 01:00 EST). Um intervalo que termina nesse instante ainda não a atravessou.
		Instant volta = ZonedDateTime.of(2026, 11, 1, 1, 0, 0, 0, NEW_YORK).withLaterOffsetAtOverlap().toInstant();
		TimeRange range = new TimeRange(volta.minusSeconds(1800), volta);
		AvailabilityPolicy domingo = new AvailabilityPolicy(NEW_YORK, List.of(window(DayOfWeek.SUNDAY, "00:00", "06:00")));

		assertTrue(domingo.allows(range));
	}

	@Test
	void outonoIntervaloNormalDepoisDaTransicaoEhPermitido() {
		TimeRange range = new TimeRange(
				ZonedDateTime.of(2026, 11, 1, 3, 0, 0, 0, NEW_YORK).toInstant(),
				ZonedDateTime.of(2026, 11, 1, 4, 0, 0, 0, NEW_YORK).toInstant());
		AvailabilityPolicy domingo = new AvailabilityPolicy(NEW_YORK, List.of(window(DayOfWeek.SUNDAY, "00:00", "06:00")));

		assertTrue(domingo.allows(range));
	}

	@Test
	void janelasColadasAtravessandoATransicaoDeVeraoSaoContinuas() {
		// 08/03/2026 em Nova York: 01:00-02:00 colada com 02:00-06:00 (o relógio pula de 02:00 para 03:00).
		AvailabilityPolicy domingo = new AvailabilityPolicy(NEW_YORK, List.of(
				window(DayOfWeek.SUNDAY, "01:00", "02:00"),
				window(DayOfWeek.SUNDAY, "02:00", "06:00")));
		TimeRange range = new TimeRange(
				ZonedDateTime.of(2026, 3, 8, 1, 30, 0, 0, NEW_YORK).toInstant(),
				ZonedDateTime.of(2026, 3, 8, 4, 0, 0, 0, NEW_YORK).toInstant());

		assertTrue(domingo.allows(range));
	}

	@Test
	void comecarExatamenteNoFimDaUltimaJanelaEhRejeitado() {
		assertFalse(politica.allows(monday(SAO_PAULO, 18, 0, 19, 0)));
	}

	@Test
	void segundosENanossegundosNoLimiteContam() {
		Instant inicio = local(SAO_PAULO, 16, 17, 0);
		TimeRange ateOFim = new TimeRange(inicio, local(SAO_PAULO, 16, 18, 0));
		TimeRange umNanoAlemDoFim = new TimeRange(inicio, local(SAO_PAULO, 16, 18, 0).plusNanos(1));
		TimeRange umNanoAntesDoInicio = new TimeRange(local(SAO_PAULO, 16, 14, 0).minusNanos(1), local(SAO_PAULO, 16, 15, 0));

		assertAll(
				() -> assertTrue(politica.allows(ateOFim)),
				() -> assertFalse(politica.allows(umNanoAlemDoFim)),
				() -> assertFalse(politica.allows(umNanoAntesDoInicio)));
	}

	@Test
	void janelaDeDomingoNaoValeQuandoOInstanteEhSegundaNoFusoDoPrestador() {
		// 01:00-02:00 UTC de segunda é domingo 22:00-23:00 em São Paulo (UTC-3).
		Instant inicio = Instant.parse("2026-11-16T01:00:00Z");
		TimeRange range = new TimeRange(inicio, inicio.plusSeconds(3600));
		AvailabilityPolicy soSegunda = new AvailabilityPolicy(SAO_PAULO, List.of(window(DayOfWeek.MONDAY, "00:00", "06:00")));
		AvailabilityPolicy domingoNoite = new AvailabilityPolicy(SAO_PAULO, List.of(window(DayOfWeek.SUNDAY, "20:00", "23:30")));

		assertAll(
				() -> assertFalse(soSegunda.allows(range)),
				() -> assertTrue(domingoNoite.allows(range)));
	}

	// --- validação da configuração ---

	@Test
	void janelasSobrepostasNoMesmoDiaSaoRejeitadas() {
		assertThrows(IllegalArgumentException.class, () -> new AvailabilityPolicy(SAO_PAULO, List.of(
				window(DayOfWeek.MONDAY, "08:00", "12:00"),
				window(DayOfWeek.MONDAY, "11:59", "18:00"))));
	}

	@Test
	void janelasDeDiasDiferentesNaoContamComoSobrepostas() {
		AvailabilityPolicy ok = new AvailabilityPolicy(SAO_PAULO, List.of(
				window(DayOfWeek.MONDAY, "08:00", "12:00"),
				window(DayOfWeek.TUESDAY, "08:00", "12:00")));

		assertEquals(2, ok.windows().size());
	}

	@Test
	void janelaInvalidaEhRejeitada() {
		assertAll(
				() -> assertThrows(IllegalArgumentException.class, () -> window(DayOfWeek.MONDAY, "12:00", "12:00")),
				() -> assertThrows(IllegalArgumentException.class, () -> window(DayOfWeek.MONDAY, "18:00", "08:00")),
				() -> assertThrows(NullPointerException.class, () -> new AvailabilityWindow(null, LocalTime.NOON, LocalTime.MAX)));
	}

	@Test
	void argumentosNulosSaoRejeitados() {
		assertAll(
				() -> assertThrows(NullPointerException.class, () -> new AvailabilityPolicy(null, List.of())),
				() -> assertThrows(NullPointerException.class, () -> new AvailabilityPolicy(SAO_PAULO, null)),
				() -> assertThrows(NullPointerException.class, () -> politica.allows(null)));
	}

	@Test
	void listaOriginalAlteradaDepoisNaoMudaAPolitica() {
		List<AvailabilityWindow> lista = new ArrayList<>(List.of(window(DayOfWeek.MONDAY, "08:00", "12:00")));
		AvailabilityPolicy copia = new AvailabilityPolicy(SAO_PAULO, lista);
		lista.add(window(DayOfWeek.MONDAY, "14:00", "18:00"));

		assertAll(
				() -> assertEquals(1, copia.windows().size()),
				() -> assertFalse(copia.allows(monday(SAO_PAULO, 15, 0, 16, 0))),
				() -> assertThrows(UnsupportedOperationException.class, () -> copia.windows().add(window(DayOfWeek.FRIDAY, "08:00", "09:00"))));
	}
}
