package com.queuecare.scheduling.application;

/** Exceções do cancelamento e da consulta de agendamentos. A camada web traduz cada uma para HTTP. */
public final class AppointmentExceptions {

	private AppointmentExceptions() {
	}

	/**
	 * O agendamento não existe OU não pertence a quem pediu. As duas situações têm a mesma resposta (404) para não
	 * revelar a existência de agendamentos de outras pessoas.
	 */
	public static class AppointmentNotFoundException extends RuntimeException {
		public AppointmentNotFoundException() {
			super("agendamento não encontrado");
		}
	}

	/** O pedido de cancelamento foi recusado por regra de negócio. */
	public static class CancellationRejectedException extends RuntimeException {

		/** Motivos de recusa. */
		public enum Reason {
			/** Já estava cancelado (409). */
			ALREADY_CANCELLED,
			/** Já iniciado, em atendimento ou concluído: não se cancela mais (409). */
			NOT_CANCELLABLE,
			/** Fora do prazo de cancelamento do prestador (422). */
			TOO_LATE,
			/** Outra requisição alterou o mesmo agendamento ao mesmo tempo (409). */
			CONCURRENT_UPDATE
		}

		private final Reason reason;

		public CancellationRejectedException(Reason reason) {
			super(reason.name());
			this.reason = reason;
		}

		public Reason reason() {
			return reason;
		}
	}

	/** O pedido de remarcação foi recusado por regra de negócio (ADR 0017). */
	public static class RescheduleRejectedException extends RuntimeException {

		/** Motivos de recusa. */
		public enum Reason {
			/** Já estava cancelado (409). */
			ALREADY_CANCELLED,
			/** Já chegou, está em atendimento ou foi concluído: não se remarca (409). */
			NOT_RESCHEDULABLE,
			/** Cliente: o agendamento atual começa dentro do prazo de cancelamento do prestador (422). */
			TOO_LATE,
			/** O novo horário é o mesmo do atual (422). */
			NO_CHANGE,
			/** Cliente: já usou as 3 remarcações permitidas deste agendamento (422). */
			RESCHEDULE_LIMIT,
			/** Outra requisição alterou o mesmo agendamento ao mesmo tempo (409). */
			CONCURRENT_UPDATE
		}

		private final Reason reason;

		public RescheduleRejectedException(Reason reason) {
			super(reason.name());
			this.reason = reason;
		}

		public Reason reason() {
			return reason;
		}
	}

	/** O pedido de check-in ou de conclusão foi recusado por regra de negócio. */
	public static class AttendanceRejectedException extends RuntimeException {

		/** Motivos de recusa. */
		public enum Reason {
			/** O agendamento está cancelado (409). */
			ALREADY_CANCELLED,
			/** A situação atual não permite a transição: check-in só a partir de SCHEDULED, conclusão só a partir de CHECKED_IN (409). */
			INVALID_STATE,
			/** O check-in só vale no dia do agendamento, no fuso do prestador (422). */
			WRONG_DAY,
			/** Faltou só pode ser registrado a partir do horário marcado (422). */
			TOO_EARLY,
			/** Outra requisição alterou o mesmo agendamento ao mesmo tempo (409). */
			CONCURRENT_UPDATE
		}

		private final Reason reason;

		public AttendanceRejectedException(Reason reason) {
			super(reason.name());
			this.reason = reason;
		}

		public Reason reason() {
			return reason;
		}
	}
}
