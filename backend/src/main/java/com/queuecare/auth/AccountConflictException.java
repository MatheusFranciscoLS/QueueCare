package com.queuecare.auth;

/**
 * Não foi possível criar a conta porque o e-mail já está em uso. Resposta UNIFORME de propósito (HTTP 409, mesmo corpo para
 * qualquer perfil da conta existente): quem cria contas para outras pessoas não descobre se o e-mail era de um cliente, de um
 * prestador, de um atendente ou de um administrador.
 */
public class AccountConflictException extends RuntimeException {

	public AccountConflictException() {
		super("conta não criada");
	}
}
