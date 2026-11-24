package com.queuecare.scheduling.application;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.Test;

/** "Há quantos dias o cliente sumiu" conta dias de CALENDÁRIO do estúdio: a mesma visita dá o mesmo número de manhã e à tarde (antes dava 70 à tarde e 69 de manhã). */
class ReportCalendarDaysTest {

	private static final ZoneId SP = ZoneId.of("America/Sao_Paulo");

	private static Instant at(int year, int month, int day, int hour, int minute) {
		return ZonedDateTime.of(year, month, day, hour, minute, 0, 0, SP).toInstant();
	}

	@Test
	void amesmaVisitaDaOMesmoNumeroDeDiasDeManhaEATarde() {
		Instant lastVisit = at(2026, 9, 15, 10, 1); // 70 dias de calendário antes de 24/11
		assertEquals(70, ReportService.calendarDaysSince(lastVisit, at(2026, 11, 24, 1, 13), SP), "de madrugada");
		assertEquals(70, ReportService.calendarDaysSince(lastVisit, at(2026, 11, 24, 9, 59), SP), "de manhã, antes do horário da visita");
		assertEquals(70, ReportService.calendarDaysSince(lastVisit, at(2026, 11, 24, 18, 30), SP), "à tarde");
		assertEquals(70, ReportService.calendarDaysSince(lastVisit, at(2026, 11, 24, 23, 59), SP), "no fim do dia");
		assertEquals(71, ReportService.calendarDaysSince(lastVisit, at(2026, 11, 25, 0, 1), SP), "passou da meia-noite do estúdio");
	}

	@Test
	void oDiaEODoEstudioENaoODeUtc() {
		// 23h30 em São Paulo ainda é o dia 15; em UTC já é dia 16. Visto às 00h30 de São Paulo do dia 17, passaram 2 dias de calendário do estúdio.
		Instant lastVisit = at(2026, 11, 15, 23, 30);
		assertEquals(2, ReportService.calendarDaysSince(lastVisit, at(2026, 11, 17, 0, 30), SP));
	}

	@Test
	void mesmoDiaDaZero() {
		assertEquals(0, ReportService.calendarDaysSince(at(2026, 11, 24, 8, 0), at(2026, 11, 24, 20, 0), SP));
	}
}
