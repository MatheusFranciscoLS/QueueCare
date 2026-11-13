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

	/** A senha não cumpre a política (HTTP 400). */
	static class InvalidPasswordException extends RuntimeException {
		InvalidPasswordException(String message) {
			super(message);
		}
	}
}
