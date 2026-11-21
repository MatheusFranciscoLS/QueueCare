package com.queuecare.scheduling.persistence;

/**
 * Situação do agendamento (coluna appointments.status). CANCELLED e NO_SHOW liberam o horário (ADR 0002 e ADR 0021). O atendimento anda só para frente:
 * SCHEDULED, CHECKED_IN (chegou), IN_PROGRESS (em atendimento), READY (pronto para retirada) e COMPLETED (retirado); ADR 0019.
 * NO_SHOW (faltou, ADR 0021): estado final, só a equipe registra, só de SCHEDULED e só a partir do horário marcado.
 */
public enum AppointmentStatus {
	SCHEDULED, CHECKED_IN, IN_PROGRESS, READY, COMPLETED, CANCELLED, NO_SHOW
}
