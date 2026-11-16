package com.queuecare.scheduling.persistence;

/** Tipos de evento do histórico (coluna appointment_events.event_type). */
public enum AppointmentEventType {
	SCHEDULED, CANCELLED, CHECKED_IN, COMPLETED,
	/** Remarcação (ADR 0017): é o ÚNICO tipo que pode se repetir no mesmo agendamento. */
	RESCHEDULED
}
