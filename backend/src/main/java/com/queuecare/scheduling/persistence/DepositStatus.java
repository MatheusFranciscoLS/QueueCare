package com.queuecare.scheduling.persistence;

/** Situação do sinal do agendamento (ADR 0023): sem sinal, pedido (pendente) ou recebido (marcado pela equipe). */
public enum DepositStatus {
	NONE, REQUESTED, RECEIVED
}
