package com.queuecare.admin;

/** Exceções da administração de usuários; cada uma vira uma resposta HTTP em {@link AdminExceptionHandler}. */
public final class AdminExceptions {

	private AdminExceptions() {
	}

	/** O usuário alvo não existe (HTTP 404). */
	public static class UserNotFoundException extends RuntimeException {
		public UserNotFoundException() {
			super("usuário não encontrado");
		}
	}

	/** O ADMIN tentou desativar a PRÓPRIA conta (HTTP 409). */
	public static class CannotDeactivateSelfException extends RuntimeException {
		public CannotDeactivateSelfException() {
			super("não é possível desativar a própria conta");
		}
	}

	/** A operação deixaria o sistema sem nenhum ADMIN ativo (HTTP 409). */
	public static class LastActiveAdminException extends RuntimeException {
		public LastActiveAdminException() {
			super("é preciso manter ao menos um administrador ativo");
		}
	}
}
