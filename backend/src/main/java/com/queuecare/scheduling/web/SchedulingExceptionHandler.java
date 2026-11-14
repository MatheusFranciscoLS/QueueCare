package com.queuecare.scheduling.web;

import com.queuecare.scheduling.application.AppointmentExceptions.AppointmentNotFoundException;
import com.queuecare.scheduling.application.AppointmentExceptions.AttendanceRejectedException;
import com.queuecare.scheduling.application.AppointmentExceptions.CancellationRejectedException;
import com.queuecare.scheduling.application.BookingConflictException;
import com.queuecare.scheduling.application.BookingRejectedException;
import com.queuecare.scheduling.application.ProviderExceptions.InvalidConfigurationException;
import com.queuecare.scheduling.application.ProviderExceptions.ProviderAlreadyConfiguredException;
import com.queuecare.scheduling.application.ProviderExceptions.ProviderNotConfiguredException;
import com.queuecare.scheduling.application.ProviderExceptions.ServiceNotFoundException;
import com.queuecare.scheduling.application.ProviderExceptions.StaffAlreadyAssignedException;
import com.queuecare.scheduling.application.ProviderExceptions.StaffNotFoundException;
import org.springframework.dao.PessimisticLockingFailureException;
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

	/** Espera de bloqueio estourou o limite (55P03) ou deadlock (40P01): conflito transitório, o cliente pode repetir. */
	@ExceptionHandler(PessimisticLockingFailureException.class)
	ProblemDetail resourceBusy(PessimisticLockingFailureException e) {
		return problem(HttpStatus.CONFLICT, "RESOURCE_BUSY", "recurso ocupado; tente novamente");
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

	@ExceptionHandler(AttendanceRejectedException.class)
	ProblemDetail attendanceRejected(AttendanceRejectedException e) {
		HttpStatus status = e.reason() == AttendanceRejectedException.Reason.WRONG_DAY ? HttpStatus.UNPROCESSABLE_ENTITY : HttpStatus.CONFLICT;
		return problem(status, e.reason().name(), "operação recusada");
	}

	@ExceptionHandler(ProviderAlreadyConfiguredException.class)
	ProblemDetail providerAlreadyConfigured(ProviderAlreadyConfiguredException e) {
		return problem(HttpStatus.CONFLICT, "PROVIDER_ALREADY_CONFIGURED", "o prestador já tem um negócio cadastrado");
	}

	@ExceptionHandler(ProviderNotConfiguredException.class)
	ProblemDetail providerNotConfigured(ProviderNotConfiguredException e) {
		return problem(HttpStatus.NOT_FOUND, "PROVIDER_NOT_CONFIGURED", "negócio ainda não cadastrado");
	}

	@ExceptionHandler(ServiceNotFoundException.class)
	ProblemDetail serviceNotFound(ServiceNotFoundException e) {
		return problem(HttpStatus.NOT_FOUND, "SERVICE_NOT_FOUND", "serviço não encontrado");
	}

	@ExceptionHandler(StaffNotFoundException.class)
	ProblemDetail staffNotFound(StaffNotFoundException e) {
		return problem(HttpStatus.NOT_FOUND, "STAFF_NOT_FOUND", "atendente não encontrado");
	}

	@ExceptionHandler(StaffAlreadyAssignedException.class)
	ProblemDetail staffAlreadyAssigned(StaffAlreadyAssignedException e) {
		return problem(HttpStatus.CONFLICT, "STAFF_ALREADY_ASSIGNED", "atendente já vinculado");
	}

	@ExceptionHandler(InvalidConfigurationException.class)
	ProblemDetail invalidConfiguration(InvalidConfigurationException e) {
		ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "INVALID_CONFIGURATION", "dados inválidos");
		problem.setProperty("errors", e.errors());
		return problem;
	}

	private static ProblemDetail problem(HttpStatus status, String reason, String detail) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
		problem.setProperty("reason", reason);
		return problem;
	}
}
