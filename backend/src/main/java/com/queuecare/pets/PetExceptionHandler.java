package com.queuecare.pets;

import com.queuecare.pets.PetExceptions.InvalidPetException;
import com.queuecare.pets.PetExceptions.PetLimitReachedException;
import com.queuecare.pets.PetExceptions.PetNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Traduz as exceções de pets para HTTP, com código estável em {@code reason}. Nenhuma resposta repete o que o tutor enviou
 * (as observações são dado pessoal): os erros de validação trazem só o motivo por campo.
 */
@RestControllerAdvice
class PetExceptionHandler {

	@ExceptionHandler(PetNotFoundException.class)
	ProblemDetail notFound(PetNotFoundException e) {
		return problem(HttpStatus.NOT_FOUND, "PET_NOT_FOUND", "pet não encontrado");
	}

	@ExceptionHandler(PetLimitReachedException.class)
	ProblemDetail limit(PetLimitReachedException e) {
		return problem(HttpStatus.CONFLICT, "PET_LIMIT_REACHED", e.getMessage());
	}

	@ExceptionHandler(InvalidPetException.class)
	ProblemDetail invalid(InvalidPetException e) {
		ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "INVALID_PET", "dados inválidos");
		problem.setProperty("errors", e.errors());
		return problem;
	}

	private static ProblemDetail problem(HttpStatus status, String reason, String detail) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
		problem.setProperty("reason", reason);
		return problem;
	}
}
