package com.queuecare.scheduling.application;

/** Recusas da lista de espera (ADR 0022). */
public final class WaitlistExceptions {

	private WaitlistExceptions() {
	}

	/** A entrada não existe OU não é de quem pergunta: a mesma resposta (404), sem pista. */
	public static class WaitlistEntryNotFoundException extends RuntimeException {
		public WaitlistEntryNotFoundException() {
			super("WAITLIST_ENTRY_NOT_FOUND");
		}
	}

	public static class WaitlistRejectedException extends RuntimeException {

		public enum Reason {
			/** O tutor já tem entradas ativas demais (422). */
			WAITLIST_LIMIT,
			/** O dia pedido já passou ou está além do que a agenda oferece (422). */
			INVALID_DATE
		}

		private final Reason reason;

		public WaitlistRejectedException(Reason reason) {
			super(reason.name());
			this.reason = reason;
		}

		public Reason reason() {
			return reason;
		}
	}
}
