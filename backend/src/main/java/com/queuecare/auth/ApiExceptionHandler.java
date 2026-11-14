package com.queuecare.auth;

import com.queuecare.auth.AuthExceptions.EmailAlreadyRegisteredException;
import com.queuecare.auth.AuthExceptions.InvalidCredentialsException;
import com.queuecare.auth.AuthExceptions.InvalidPasswordException;
import com.queuecare.auth.AuthExceptions.TooManyLoginAttemptsException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Erros em JSON (ProblemDetail). Nunca devolve valores enviados (para não repetir senhas). Os demais erros seguem o
 * tratamento padrão do Spring Boot, que não expõe stack trace (404, 405, 500 mantêm seus códigos corretos).
 */
@RestControllerAdvice
class ApiExceptionHandler {

	@ExceptionHandler(MethodArgumentNotValidException.class)
	ProblemDetail invalidBody(MethodArgumentNotValidException e) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "dados inválidos");
		Map<String, String> errors = new LinkedHashMap<>();
		e.getBindingResult().getFieldErrors().forEach(f -> errors.putIfAbsent(f.getField(), f.getDefaultMessage()));
		problem.setProperty("errors", errors);
		return problem;
	}

	@ExceptionHandler(HttpMessageNotReadableException.class)
	ProblemDetail unreadable(HttpMessageNotReadableException e) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "corpo da requisição inválido");
	}

	@ExceptionHandler(InvalidPasswordException.class)
	ProblemDetail invalidPassword(InvalidPasswordException e) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "dados inválidos");
		problem.setProperty("errors", Map.of("password", e.getMessage()));
		return problem;
	}

	@ExceptionHandler(EmailAlreadyRegisteredException.class)
	ProblemDetail emailAlreadyRegistered(EmailAlreadyRegisteredException e) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "e-mail já cadastrado");
	}

	@ExceptionHandler(AccountConflictException.class)
	ProblemDetail accountConflict(AccountConflictException e) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "não foi possível criar a conta com os dados informados");
		problem.setProperty("reason", "ACCOUNT_CONFLICT");
		return problem;
	}

	@ExceptionHandler(TooManyLoginAttemptsException.class)
	ResponseEntity<ProblemDetail> tooManyAttempts(TooManyLoginAttemptsException e) {
		// Mesma resposta para e-mail existente ou não: o bloqueio conta TODA falha, então não revela contas.
		return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).header(HttpHeaders.RETRY_AFTER, Long.toString(e.retryAfterSeconds()))
				.body(ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS, "muitas tentativas; tente novamente mais tarde"));
	}

	@ExceptionHandler(InvalidCredentialsException.class)
	ProblemDetail invalidCredentials(InvalidCredentialsException e) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "credenciais inválidas");
	}
}
