package com.queuecare.admin;

import com.queuecare.admin.AdminExceptions.CannotDeactivateSelfException;
import com.queuecare.admin.AdminExceptions.LastActiveAdminException;
import com.queuecare.admin.AdminExceptions.UserNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Respostas estáveis (campo {@code reason}) da administração de usuários, sem repetir dados da requisição. */
@RestControllerAdvice
class AdminExceptionHandler {

	@ExceptionHandler(UserNotFoundException.class)
	ProblemDetail notFound(UserNotFoundException e) {
		return problem(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "usuário não encontrado");
	}

	@ExceptionHandler(CannotDeactivateSelfException.class)
	ProblemDetail self(CannotDeactivateSelfException e) {
		return problem(HttpStatus.CONFLICT, "CANNOT_DEACTIVATE_SELF", "não é possível desativar a própria conta");
	}

	@ExceptionHandler(LastActiveAdminException.class)
	ProblemDetail lastAdmin(LastActiveAdminException e) {
		return problem(HttpStatus.CONFLICT, "LAST_ACTIVE_ADMIN", "é preciso manter ao menos um administrador ativo");
	}

	private static ProblemDetail problem(HttpStatus status, String reason, String detail) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
		problem.setProperty("reason", reason);
		return problem;
	}
}
