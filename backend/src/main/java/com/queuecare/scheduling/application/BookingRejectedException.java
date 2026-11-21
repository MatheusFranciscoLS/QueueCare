package com.queuecare.scheduling.application;

/**
 * O pedido foi recusado por uma regra de negócio ou dado inválido (não é conflito de horário).
 * A camada web traduzirá o motivo para 400, 404 ou 422 conforme o caso.
 */
public class BookingRejectedException extends RuntimeException {

	/** Motivos de recusa. */
	public enum Reason {
		PROVIDER_NOT_FOUND,
		SERVICE_NOT_FOUND_OR_INACTIVE,
		CUSTOMER_NOT_FOUND_OR_INACTIVE,
		/** O pedido não trouxe o pet (400): todo agendamento novo tem pet. */
		PET_REQUIRED,
		/** Pet inexistente OU de outro cliente: a mesma resposta (404), para não revelar quais pets existem. */
		PET_NOT_FOUND,
		/** O pet é do cliente, mas está arquivado (422). */
		PET_INACTIVE,
		/** Horário local que não existe (pulado pelo horário de verão). */
		NONEXISTENT_LOCAL_TIME,
		/** Horário local que acontece duas vezes (volta do horário de verão). */
		AMBIGUOUS_LOCAL_TIME,
		IN_THE_PAST,
		TOO_SOON,
		/** R1: fora do expediente ou não cabe inteiro nele. */
		OUTSIDE_AVAILABILITY,
		/** ADR 0024: o estúdio está fechado naquele dia (folga, feriado ou férias). */
		CLOSED_DAY
	}

	private final Reason reason;

	public BookingRejectedException(Reason reason) {
		super(reason.name());
		this.reason = reason;
	}

	public Reason reason() {
		return reason;
	}
}
