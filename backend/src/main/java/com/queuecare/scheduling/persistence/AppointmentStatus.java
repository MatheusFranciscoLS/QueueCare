package com.queuecare.scheduling.persistence;

/** Situação do agendamento (coluna appointments.status). Só CANCELLED libera o horário (ADR 0002). */
public enum AppointmentStatus {
	SCHEDULED, CHECKED_IN, COMPLETED, CANCELLED
}
