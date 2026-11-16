package com.queuecare.pets;

import java.util.Map;

/** Exceções do cadastro de pets (ADR 0016). A camada web traduz para HTTP. */
public final class PetExceptions {

	private PetExceptions() {
	}

	/** Pet inexistente OU de outro cliente: a MESMA resposta (HTTP 404), para não revelar quais ids existem. */
	public static class PetNotFoundException extends RuntimeException {
		public PetNotFoundException() {
			super("pet não encontrado");
		}
	}

	/** Limite de pets ativos ou de pets no total (HTTP 409, reason PET_LIMIT_REACHED). */
	public static class PetLimitReachedException extends RuntimeException {
		public PetLimitReachedException(String message) {
			super(message);
		}
	}

	/** Dados inválidos (HTTP 400). {@code errors} traz só o motivo por campo, nunca o valor enviado. */
	public static class InvalidPetException extends RuntimeException {
		private final transient Map<String, String> errors;

		public InvalidPetException(Map<String, String> errors) {
			super("dados do pet inválidos");
			this.errors = Map.copyOf(errors);
		}

		public Map<String, String> errors() {
			return errors;
		}
	}
}
