package com.queuecare.scheduling.persistence;

/**
 * Situação do agendamento (coluna appointments.status). Só CANCELLED libera o horário (ADR 0002). O atendimento anda só para frente:
 * SCHEDULED, CHECKED_IN (chegou), IN_PROGRESS (em atendimento), READY (pronto para retirada) e COMPLETED (retirado); ADR 0019.
 */
public enum AppointmentStatus {
	SCHEDULED, CHECKED_IN, IN_PROGRESS, READY, COMPLETED, CANCELLED
}
