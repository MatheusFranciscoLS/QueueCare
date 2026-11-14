package com.queuecare.auth;

/** Exceções do cadastro e login. Cada uma vira uma resposta HTTP específica em {@link ApiExceptionHandler}. */
final class AuthExceptions {

	private AuthExceptions() {
	}

	/** O e-mail já está cadastrado (HTTP 409). */
	static class EmailAlreadyRegisteredException extends RuntimeException {
		EmailAlreadyRegisteredException() {
			super("e-mail já cadastrado");
		}
	}

	/** E-mail ou senha incorretos, ou usuário inativo. A mensagem é sempre a mesma para não revelar qual foi (HTTP 401). */
	static class InvalidCredentialsException extends RuntimeException {
		InvalidCredentialsException() {
			super("credenciais inválidas");
		}
	}

	/** Falhas de login demais para este e-mail neste endereço: bloqueio temporário (HTTP 429 com Retry-After). */
	static class TooManyLoginAttemptsException extends RuntimeException {
		private final long retryAfterSeconds;

		TooManyLoginAttemptsException(long retryAfterSeconds) {
			super("muitas tentativas");
			this.retryAfterSeconds = retryAfterSeconds;
		}

		long retryAfterSeconds() {
			return retryAfterSeconds;
		}
	}

	/** A senha atual informada na troca de senha não confere (HTTP 400, sem dizer mais nada). */
	static class InvalidCurrentPasswordException extends RuntimeException {
		InvalidCurrentPasswordException() {
			super("senha atual incorreta");
		}
	}

	/** Redefinição de senha: o usuário alvo não existe (HTTP 404). */
	static class TargetUserNotFoundException extends RuntimeException {
		TargetUserNotFoundException() {
			super("usuário não encontrado");
		}
	}

	/** O ADMIN tentou redefinir a PRÓPRIA senha por esta via: deve usar a troca de senha (HTTP 409). */
	static class SelfResetException extends RuntimeException {
		SelfResetException() {
			super("use a troca de senha");
		}
	}

	/** A senha não cumpre a política (HTTP 400). */
	static class InvalidPasswordException extends RuntimeException {
		InvalidPasswordException(String message) {
			super(message);
		}
	}
}
