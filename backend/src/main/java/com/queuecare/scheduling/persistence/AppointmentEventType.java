package com.queuecare.scheduling.persistence;

/** Tipos de evento do histórico (coluna appointment_events.event_type). */
public enum AppointmentEventType {
	SCHEDULED, CANCELLED, CHECKED_IN, COMPLETED
}
