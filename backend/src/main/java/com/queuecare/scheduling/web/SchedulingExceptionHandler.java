package com.queuecare.scheduling.web;

import com.queuecare.scheduling.application.AppointmentExceptions.AppointmentNotFoundException;
import com.queuecare.scheduling.application.AppointmentExceptions.CancellationRejectedException;
import com.queuecare.scheduling.application.BookingConflictException;
import com.queuecare.scheduling.application.BookingRejectedException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Traduz as exceções do agendamento para HTTP: conflito de horário = 409; não encontrado (ou de outra pessoa) = 404;
 * regra de negócio violada = 422. As respostas têm um código estável em {@code reason} e não repetem dados enviados.
 */
@RestControllerAdvice
class SchedulingExceptionHandler {

	@ExceptionHandler(BookingConflictException.class)
	ProblemDetail bookingConflict(BookingConflictException e) {
		return problem(HttpStatus.CONFLICT, "SLOT_TAKEN", "o horário já está ocupado");
	}

	@ExceptionHandler(BookingRejectedException.class)
	ProblemDetail bookingRejected(BookingRejectedException e) {
		HttpStatus status = switch (e.reason()) {
			case PROVIDER_NOT_FOUND, SERVICE_NOT_FOUND_OR_INACTIVE, CUSTOMER_NOT_FOUND_OR_INACTIVE -> HttpStatus.NOT_FOUND;
			case NONEXISTENT_LOCAL_TIME, AMBIGUOUS_LOCAL_TIME, IN_THE_PAST, TOO_SOON, OUTSIDE_AVAILABILITY -> HttpStatus.UNPROCESSABLE_ENTITY;
		};
		return problem(status, e.reason().name(), "agendamento recusado");
	}

	@ExceptionHandler(AppointmentNotFoundException.class)
	ProblemDetail notFound(AppointmentNotFoundException e) {
		return problem(HttpStatus.NOT_FOUND, "APPOINTMENT_NOT_FOUND", "agendamento não encontrado");
	}

	@ExceptionHandler(CancellationRejectedException.class)
	ProblemDetail cancellationRejected(CancellationRejectedException e) {
		HttpStatus status = e.reason() == CancellationRejectedException.Reason.TOO_LATE ? HttpStatus.UNPROCESSABLE_ENTITY : HttpStatus.CONFLICT;
		return problem(status, e.reason().name(), "cancelamento recusado");
	}

	private static ProblemDetail problem(HttpStatus status, String reason, String detail) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
		problem.setProperty("reason", reason);
		return problem;
	}
}
