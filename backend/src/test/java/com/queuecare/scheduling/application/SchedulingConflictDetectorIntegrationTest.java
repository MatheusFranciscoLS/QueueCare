package com.queuecare.scheduling.application;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.queuecare.scheduling.persistence.Appointment;
import com.queuecare.scheduling.persistence.AppointmentRepository;
import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Prova, com erros REAIS do PostgreSQL passando pelo Spring e pelo Hibernate, que o detector reconhece só o conflito de
 * horário (23P01 da restrição appointments_no_overlap e 40P01) e não confunde outros erros de integridade com conflito.
 */
class SchedulingConflictDetectorIntegrationTest extends BookingIntegrationTestBase {

	@Autowired
	AppointmentRepository appointments;

	@Autowired
	PlatformTransactionManager transactionManager;

	private Appointment appointment(UUID customerId, String start, String end) {
		return new Appointment(providerSp, serviceSp, customerId, Instant.parse(start), Instant.parse(end), FixedClockConfig.NOW);
	}

	@Test
	void violacaoRealDaRestricaoDeExclusaoEhReconhecidaComoConflito() {
		TransactionTemplate tx = new TransactionTemplate(transactionManager);
		tx.executeWithoutResult(s -> appointments.saveAndFlush(appointment(customer, "2026-11-17T13:00:00Z", "2026-11-17T14:00:00Z")));

		RuntimeException error = assertThrows(RuntimeException.class, () -> tx.executeWithoutResult(
				s -> appointments.saveAndFlush(appointment(customer, "2026-11-17T13:30:00Z", "2026-11-17T14:30:00Z"))));

		assertTrue(SchedulingConflictDetector.isConflict(error), "erro real do banco: " + error);
	}

	@Test
	void violacaoDeChaveEstrangeiraNaoEhConflitoDeHorario() {
		TransactionTemplate tx = new TransactionTemplate(transactionManager);

		RuntimeException error = assertThrows(RuntimeException.class, () -> tx.executeWithoutResult(
				s -> appointments.saveAndFlush(appointment(UUID.randomUUID(), "2026-11-17T13:00:00Z", "2026-11-17T14:00:00Z"))));

		assertFalse(SchedulingConflictDetector.isConflict(error), "chave estrangeira inválida não é conflito: " + error);
	}

	@Test
	void sqlStatesDeConflitoEmCadeiasMontadasAMao() {
		SQLException deadlock = new SQLException("deadlock detected", "40P01");
		SQLException exclusionOtherConstraint = new SQLException("conflicting key value violates exclusion constraint \"availability_windows_no_overlap\"", "23P01");
		SQLException exclusionBooking = new SQLException("conflicting key value violates exclusion constraint \"appointments_no_overlap\"", "23P01");
		SQLException foreignKey = new SQLException("violates foreign key constraint", "23503");

		assertTrue(SchedulingConflictDetector.isConflict(new RuntimeException("envolve", new RuntimeException("de novo", deadlock))));
		assertTrue(SchedulingConflictDetector.isConflict(new RuntimeException(exclusionBooking)));
		assertFalse(SchedulingConflictDetector.isConflict(new RuntimeException(exclusionOtherConstraint)));
		assertFalse(SchedulingConflictDetector.isConflict(new RuntimeException(foreignKey)));
		assertFalse(SchedulingConflictDetector.isConflict(new RuntimeException("sem causa SQL")));
		assertFalse(SchedulingConflictDetector.isConflict(null));
	}

	@Test
	void cadeiaDeCausasComCicloNaoTravaODetector() {
		RuntimeException a = new RuntimeException("a");
		RuntimeException b = new RuntimeException("b", a);
		a.initCause(b);

		assertFalse(SchedulingConflictDetector.isConflict(a));
	}
}
