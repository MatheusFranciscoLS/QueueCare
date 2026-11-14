package com.queuecare.auth;

import com.queuecare.audit.AuditAction;
import com.queuecare.audit.AuditService;
import com.queuecare.auth.AuthExceptions.InvalidCredentialsException;
import com.queuecare.auth.AuthExceptions.InvalidCurrentPasswordException;
import com.queuecare.auth.AuthExceptions.InvalidPasswordException;
import com.queuecare.auth.AuthExceptions.SelfResetException;
import com.queuecare.auth.AuthExceptions.TargetUserNotFoundException;
import com.queuecare.auth.AuthExceptions.TooManyLoginAttemptsException;
import com.queuecare.scheduling.persistence.UserAccount;
import com.queuecare.scheduling.persistence.UserAccountRepository;
import com.queuecare.security.RateLimitProperties;
import com.queuecare.security.RateLimiter;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Troca de senha pelo PRÓPRIO usuário (ADR 0006). Tudo numa transação, com a linha do usuário bloqueada: duas trocas
 * simultâneas acontecem uma de cada vez (a segunda já não confere a senha atual). A troca não altera perfil nem e-mail, zera
 * {@code must_change_password}, sobe a versão das credenciais (todo token anterior, inclusive o usado agora, deixa de valer)
 * e devolve um token NOVO. A senha atual é conferida com limite de tentativas por usuário (reserva atômica antes de comparar):
 * quem tem só um token roubado não consegue adivinhar a senha atual.
 */
@Service
public class PasswordService {

	private static final long LOCK_TIMEOUT_MILLIS_DEFAULT = 3_000;

	private final UserAccountRepository users;
	private final PasswordEncoder passwordEncoder;
	private final AuthService authService;
	private final AuditService audit;
	private final RateLimiter limiter;
	private final RateLimitProperties limits;
	private final RowLocks rowLocks;

	public PasswordService(UserAccountRepository users, PasswordEncoder passwordEncoder, AuthService authService, AuditService audit,
			@Qualifier(RateLimiter.LOGIN_LOCKOUT_LIMITER) RateLimiter limiter, RateLimitProperties limits, RowLocks rowLocks) {
		this.users = users;
		this.passwordEncoder = passwordEncoder;
		this.authService = authService;
		this.audit = audit;
		this.limiter = limiter;
		this.limits = limits;
		this.rowLocks = rowLocks;
	}

	/** Carrega o usuário bloqueando a linha (espera limitada, ver {@link RowLocks}). */
	private UserAccount lockUser(UUID userId) {
		return rowLocks.lockUser(userId).orElseThrow(InvalidCredentialsException::new);
	}

	@Transactional
	public AuthService.IssuedToken changePassword(UUID userId, String currentPassword, String newPassword) {
		String attemptKey = "pwchange|" + userId;
		Duration window = Duration.ofMinutes(limits.loginFailureWindowMinutes());
		if (!limiter.tryAcquire(attemptKey, limits.passwordChangeAttempts(), window)) {
			throw new TooManyLoginAttemptsException(limiter.retryAfterSeconds(attemptKey, window));
		}
		UserAccount user = lockUser(userId);
		if (!user.isActive() || !currentMatches(currentPassword, user.getPasswordHash())) {
			throw new InvalidCurrentPasswordException();
		}
		AuthService.validatePassword(newPassword);
		if (passwordEncoder.matches(newPassword, user.getPasswordHash())) {
			throw new InvalidPasswordException("a nova senha deve ser diferente da atual");
		}
		user.replacePassword(passwordEncoder.encode(newPassword), false);
		users.saveAndFlush(user);
		audit.record(user.getId(), AuditAction.PASSWORD_CHANGED, user.getId(), user.getRole());
		limiter.clear(attemptKey);
		return authService.issueToken(user);
	}

	/**
	 * Redefinição de senha de OUTRO usuário pelo ADMIN: define uma senha inicial nova (mesma política), obriga a troca no próximo
	 * acesso, invalida todos os tokens do usuário e audita (ator = ADMIN, alvo = o usuário). Não serve para a própria senha do
	 * ADMIN (use a troca de senha, que exige a senha atual) e não reativa conta inativa. Usuário inexistente: 404.
	 */
	@Transactional
	public void resetPassword(UUID adminUserId, UUID targetUserId, String newInitialPassword) {
		if (adminUserId.equals(targetUserId)) {
			throw new SelfResetException();
		}
		UserAccount target;
		try {
			target = lockUser(targetUserId);
		} catch (InvalidCredentialsException e) {
			throw new TargetUserNotFoundException();
		}
		AuthService.validatePassword(newInitialPassword);
		target.replacePassword(passwordEncoder.encode(newInitialPassword), true);
		users.saveAndFlush(target);
		audit.record(adminUserId, AuditAction.PASSWORD_RESET, target.getId(), target.getRole());
	}

	private boolean currentMatches(String candidate, String hash) {
		// Mais de 72 bytes nunca confere (e nunca é truncada em silêncio pelo BCrypt).
		return candidate.getBytes(StandardCharsets.UTF_8).length <= AuthService.MAX_PASSWORD_BYTES && passwordEncoder.matches(candidate, hash);
	}
}
