package com.queuecare.scheduling.persistence;

/** Tipos de evento do histórico (coluna appointment_events.event_type). */
public enum AppointmentEventType {
	SCHEDULED, CANCELLED, CHECKED_IN, COMPLETED,
	/** O atendimento começou e o pet ficou pronto (ADR 0019); um de cada por agendamento. */
	STARTED, READY,
	/** Remarcação (ADR 0017): é o ÚNICO tipo que pode se repetir no mesmo agendamento. */
	RESCHEDULED,
	/** A pessoa faltou (ADR 0021); um por agendamento, é o último evento. */
	NO_SHOW
}
