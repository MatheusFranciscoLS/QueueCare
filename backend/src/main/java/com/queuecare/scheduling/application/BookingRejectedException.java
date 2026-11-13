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
		/** Horário local que não existe (pulado pelo horário de verão). */
		NONEXISTENT_LOCAL_TIME,
		/** Horário local que acontece duas vezes (volta do horário de verão). */
		AMBIGUOUS_LOCAL_TIME,
		IN_THE_PAST,
		TOO_SOON,
		/** R1: fora do expediente ou não cabe inteiro nele. */
		OUTSIDE_AVAILABILITY
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
