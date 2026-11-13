package com.queuecare.scheduling.application;

import java.util.Map;

/** Exceções da configuração do negócio do prestador e da consulta de horários. A camada web traduz para HTTP. */
public final class ProviderExceptions {

	private ProviderExceptions() {
	}

	/** O usuário já tem um negócio cadastrado (HTTP 409). */
	public static class ProviderAlreadyConfiguredException extends RuntimeException {
		public ProviderAlreadyConfiguredException() {
			super("o prestador já tem um negócio cadastrado");
		}
	}

	/** O usuário ainda não cadastrou o negócio (HTTP 404). */
	public static class ProviderNotConfiguredException extends RuntimeException {
		public ProviderNotConfiguredException() {
			super("negócio ainda não cadastrado");
		}
	}

	/** Serviço inexistente OU de outro prestador: mesma resposta (HTTP 404). */
	public static class ServiceNotFoundException extends RuntimeException {
		public ServiceNotFoundException() {
			super("serviço não encontrado");
		}
	}

	/**
	 * E-mail inexistente, de usuário que não é atendente ou inativo, ou vínculo que não existe neste negócio. Todos têm a MESMA
	 * resposta (HTTP 404) para não revelar quais e-mails existem nem o perfil deles.
	 */
	public static class StaffNotFoundException extends RuntimeException {
		public StaffNotFoundException() {
			super("atendente não encontrado");
		}
	}

	/** O atendente já está vinculado (a este ou a outro prestador): HTTP 409, sem detalhes do vínculo existente. */
	public static class StaffAlreadyAssignedException extends RuntimeException {
		public StaffAlreadyAssignedException() {
			super("atendente já vinculado");
		}
	}

	/** Valores de configuração inválidos (HTTP 400), com o campo e a mensagem de cada erro. */
	public static class InvalidConfigurationException extends RuntimeException {

		private final Map<String, String> errors;

		public InvalidConfigurationException(String field, String message) {
			super(message);
			this.errors = Map.of(field, message);
		}

		public Map<String, String> errors() {
			return errors;
		}
	}
}
