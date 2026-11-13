package com.queuecare.scheduling.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.queuecare.scheduling.application.BookingRejectedException.Reason;
import com.queuecare.scheduling.persistence.Appointment;
import com.queuecare.scheduling.persistence.AppointmentEvent;
import com.queuecare.scheduling.persistence.AppointmentEventRepository;
import com.queuecare.scheduling.persistence.AppointmentEventType;
import com.queuecare.scheduling.persistence.AppointmentStatus;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Agendamento ponta a ponta (sem HTTP): R5, R1 e R2 + persistência, com Spring completo e PostgreSQL real.
 * Relógio fixo: segunda 16/11/2026 09:00 em São Paulo. O prestador de SP exige 60 min de antecedência.
 */
class BookingServiceIntegrationTest extends BookingIntegrationTestBase {

	@Autowired
	BookingService bookingService;

	@Autowired
	AppointmentEventRepository eventRepository;

	private BookingRequest sp(int day, int hour, int minute) {
		return new BookingRequest(providerSp, serviceSp, customer, LocalDateTime.of(2026, 11, day, hour, minute));
	}

	private Reason rejectedReason(BookingRequest request) {
		return assertThrows(BookingRejectedException.class, () -> bookingService.book(request)).reason();
	}

	// --- caminho feliz ---

	@Test
	void agendaConvertendoOHorarioLocalDoPrestadorParaUtcECalculandoOFimPelaDuracao() {
		Appointment appointment = bookingService.book(sp(17, 10, 0));

		assertEquals(Instant.parse("2026-11-17T13:00:00Z"), appointment.getStartsAt());
		assertEquals(Instant.parse("2026-11-17T14:00:00Z"), appointment.getEndsAt());
		assertEquals(AppointmentStatus.SCHEDULED, appointment.getStatus());
		assertEquals(FixedClockConfig.NOW, appointment.getCreatedAt());
		assertEquals(1, count("SELECT count(*) FROM appointments"));
	}

	@Test
	void registraOEventoDeAgendamentoNoHistorico() {
		Appointment appointment = bookingService.book(sp(17, 10, 0));

		List<AppointmentEvent> events = eventRepository.findByAppointmentId(appointment.getId());
		assertEquals(1, events.size());
		assertEquals(AppointmentEventType.SCHEDULED, events.get(0).getEventType());
		assertEquals(customer, events.get(0).getActorId());
	}

	@Test
	void agendamentosConsecutivosSaoAceitos() {
		bookingService.book(sp(17, 10, 0));
		bookingService.book(sp(17, 11, 0));
		bookingService.book(sp(17, 9, 0));

		assertEquals(3, count("SELECT count(*) FROM appointments"));
	}

	// --- R2: conflito ---

	@Test
	void mesmoHorarioEhConflito() {
		bookingService.book(sp(17, 10, 0));

		assertThrows(BookingConflictException.class, () -> bookingService.book(sp(17, 10, 0)));
		assertEquals(1, count("SELECT count(*) FROM appointments"));
	}

	@Test
	void sobreposicaoParcialEhConflito() {
		bookingService.book(sp(17, 10, 0));

		assertThrows(BookingConflictException.class, () -> bookingService.book(sp(17, 10, 30)));
		assertThrows(BookingConflictException.class, () -> bookingService.book(sp(17, 9, 30)));
	}

	@Test
	void agendamentoCanceladoLiberaOHorario() {
		Appointment first = bookingService.book(sp(17, 10, 0));
		jdbc.update("UPDATE appointments SET status = 'CANCELLED' WHERE id = ?", first.getId());

		bookingService.book(sp(17, 10, 0));

		assertEquals(1, count("SELECT count(*) FROM appointments WHERE status <> 'CANCELLED'"));
	}

	@Test
	void outroPrestadorTemOutraAgenda() {
		UUID otherProvider = provider(user("outro@exemplo.test", "PROVIDER", true), "America/Sao_Paulo", 0);
		UUID otherService = service(otherProvider, 60, true);
		window(otherProvider, 2, "08:00", "12:00");
		bookingService.book(sp(17, 10, 0));

		bookingService.book(new BookingRequest(otherProvider, otherService, customer, LocalDateTime.of(2026, 11, 17, 10, 0)));

		assertEquals(2, count("SELECT count(*) FROM appointments"));
	}

	// --- R1: expediente ---

	@Test
	void t2_servicoDe60MinutosAs1730ComExpedienteAte18hEhRejeitado() {
		assertEquals(Reason.OUTSIDE_AVAILABILITY, rejectedReason(sp(17, 17, 30)));
	}

	@Test
	void horarioDoAlmocoEhRejeitado() {
		assertEquals(Reason.OUTSIDE_AVAILABILITY, rejectedReason(sp(17, 12, 0)));
		assertEquals(Reason.OUTSIDE_AVAILABILITY, rejectedReason(sp(17, 11, 30)));
	}

	@Test
	void ultimoHorarioQueCabeNoExpedienteEhAceito() {
		bookingService.book(sp(17, 17, 0));

		assertEquals(1, count("SELECT count(*) FROM appointments"));
	}

	@Test
	void diaSemJanelaEhRejeitado() {
		jdbc.update("DELETE FROM availability_windows WHERE provider_id = ? AND day_of_week = 2", providerSp);

		assertEquals(Reason.OUTSIDE_AVAILABILITY, rejectedReason(sp(17, 10, 0)));
	}

	// --- R5: passado e antecedência ---

	@Test
	void horarioNoPassadoEhRejeitado() {
		// Agora são 09:00 de segunda em São Paulo.
		assertEquals(Reason.IN_THE_PAST, rejectedReason(sp(16, 8, 0)));
	}

	@Test
	void horarioAntesDaAntecedenciaMinimaEhRejeitado() {
		assertEquals(Reason.TOO_SOON, rejectedReason(sp(16, 9, 30)));
	}

	@Test
	void horarioExatamenteNoLimiteDaAntecedenciaEhAceito() {
		bookingService.book(sp(16, 10, 0));

		assertEquals(1, count("SELECT count(*) FROM appointments"));
	}

	@Test
	void horarioLocalInexistenteNoHorarioDeVeraoEhRejeitado() {
		// 14/03/2027 02:30 não existe em Nova York (o relógio pula de 02:00 para 03:00).
		BookingRequest request = new BookingRequest(providerNy, serviceNy, customer, LocalDateTime.of(2027, 3, 14, 2, 30));

		assertEquals(Reason.NONEXISTENT_LOCAL_TIME, rejectedReason(request));
	}

	@Test
	void horarioLocalAmbiguoNoHorarioDeVeraoEhRejeitado() {
		// 07/11/2027 01:30 acontece duas vezes em Nova York.
		BookingRequest request = new BookingRequest(providerNy, serviceNy, customer, LocalDateTime.of(2027, 11, 7, 1, 30));

		assertEquals(Reason.AMBIGUOUS_LOCAL_TIME, rejectedReason(request));
	}

	@Test
	void horarioValidoDeNovaYorkVirouUtcCorretamente() {
		// 14/03/2027 03:00 EDT (UTC-4) = 07:00 UTC.
		Appointment appointment = bookingService
				.book(new BookingRequest(providerNy, serviceNy, customer, LocalDateTime.of(2027, 3, 14, 3, 0)));

		assertEquals(Instant.parse("2027-03-14T07:00:00Z"), appointment.getStartsAt());
	}

	// --- dados inválidos ---

	@Test
	void prestadorInexistenteEhRejeitado() {
		BookingRequest request = new BookingRequest(UUID.randomUUID(), serviceSp, customer, LocalDateTime.of(2026, 11, 17, 10, 0));

		assertEquals(Reason.PROVIDER_NOT_FOUND, rejectedReason(request));
	}

	@Test
	void servicoDeOutroPrestadorOuInativoEhRejeitado() {
		BookingRequest withOtherProvidersService = new BookingRequest(providerSp, serviceNy, customer, LocalDateTime.of(2026, 11, 17, 10, 0));
		UUID inactive = service(providerSp, 60, false);
		BookingRequest withInactiveService = new BookingRequest(providerSp, inactive, customer, LocalDateTime.of(2026, 11, 17, 10, 0));

		assertEquals(Reason.SERVICE_NOT_FOUND_OR_INACTIVE, rejectedReason(withOtherProvidersService));
		assertEquals(Reason.SERVICE_NOT_FOUND_OR_INACTIVE, rejectedReason(withInactiveService));
	}

	@Test
	void clienteInexistenteOuInativoEhRejeitado() {
		UUID inactiveCustomer = user("inativo@exemplo.test", "CUSTOMER", false);

		assertEquals(Reason.CUSTOMER_NOT_FOUND_OR_INACTIVE,
				rejectedReason(new BookingRequest(providerSp, serviceSp, UUID.randomUUID(), LocalDateTime.of(2026, 11, 17, 10, 0))));
		assertEquals(Reason.CUSTOMER_NOT_FOUND_OR_INACTIVE,
				rejectedReason(new BookingRequest(providerSp, serviceSp, inactiveCustomer, LocalDateTime.of(2026, 11, 17, 10, 0))));
	}

	@Test
	void falhaAoGravarOEventoDesfazTambemOAgendamentoEOErroNaoViraConflito() {
		// Gatilho de teste que faz o INSERT em appointment_events falhar, depois do INSERT do agendamento.
		jdbc.execute("CREATE FUNCTION test_fail_event() RETURNS trigger AS $$ BEGIN RAISE EXCEPTION 'falha simulada'; END; $$ LANGUAGE plpgsql");
		jdbc.execute("CREATE TRIGGER test_fail_event_trg BEFORE INSERT ON appointment_events FOR EACH ROW EXECUTE FUNCTION test_fail_event()");
		try {
			RuntimeException error = assertThrows(RuntimeException.class, () -> bookingService.book(sp(17, 10, 0)));

			assertFalse(error instanceof BookingConflictException, "falha do evento não é conflito de horário: " + error);
			assertEquals(0, count("SELECT count(*) FROM appointments"), "o agendamento deve sofrer rollback junto");
			assertEquals(0, count("SELECT count(*) FROM appointment_events"));
		} finally {
			jdbc.execute("DROP TRIGGER test_fail_event_trg ON appointment_events");
			jdbc.execute("DROP FUNCTION test_fail_event()");
		}
		// Depois de remover o gatilho, o mesmo horário volta a poder ser agendado (nada ficou preso).
		bookingService.book(sp(17, 10, 0));
		assertEquals(1, count("SELECT count(*) FROM appointments"));
	}

	@Test
	void pedidoRecusadoNaoGravaNada() {
		assertEquals(Reason.OUTSIDE_AVAILABILITY, rejectedReason(sp(17, 17, 30)));

		assertEquals(0, count("SELECT count(*) FROM appointments"));
		assertEquals(0, count("SELECT count(*) FROM appointment_events"));
		assertNull(jdbc.queryForObject("SELECT max(id::text) FROM appointment_events", String.class));
	}
}
