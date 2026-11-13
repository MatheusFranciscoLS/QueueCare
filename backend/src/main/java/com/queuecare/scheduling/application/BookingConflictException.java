package com.queuecare.scheduling.application;

/**
 * O horário já está ocupado (ou foi ocupado por outra requisição ao mesmo tempo). A camada web traduzirá para HTTP 409.
 */
public class BookingConflictException extends RuntimeException {

	public BookingConflictException() {
		super("O horário já está ocupado");
	}

	public BookingConflictException(Throwable cause) {
		super("O horário já está ocupado", cause);
	}
}
