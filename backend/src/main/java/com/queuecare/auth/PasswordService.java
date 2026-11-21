package com.queuecare.auth;

import com.queuecare.audit.AuditAction;
import com.queuecare.audit.AuditService;
import com.queuecare.auth.AuthExceptions.InvalidCredentialsException;
import com.queuecare.auth.AuthExceptions.InvalidCurrentPasswordException;
import com.queuecare.auth.AuthExceptions.InvalidPasswordException;
import com.queuecare.auth.AuthExceptions.SelfResetException;
import com.queuecare.auth.AuthExceptions.TargetUserNotFoundException;
import com.queuecare.auth.AuthExceptions.TooManyLoginAttemptsException;
import com.queuecare.scheduling.persistence.AppointmentRepository;
import com.queuecare.scheduling.persistence.ProviderRepository;
import com.queuecare.scheduling.persistence.UserAccount;
import com.queuecare.scheduling.persistence.UserAccountRepository;
import com.queuecare.security.RateLimitProperties;
import com.queuecare.security.RateLimiter;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
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
	private final ProviderRepository providers;
	private final AppointmentRepository appointments;
	private final SecureRandom random = new SecureRandom();

	public PasswordService(UserAccountRepository users, PasswordEncoder passwordEncoder, AuthService authService, AuditService audit,
			@Qualifier(RateLimiter.LOGIN_LOCKOUT_LIMITER) RateLimiter limiter, RateLimitProperties limits, RowLocks rowLocks, ProviderRepository providers,
			AppointmentRepository appointments) {
		this.providers = providers;
		this.appointments = appointments;
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

	/** Sem 0/O, 1/l/I e outros caracteres que se confundem ao ler a senha num WhatsApp. */
	private static final String TEMPORARY_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz23456789";
	static final int TEMPORARY_PASSWORD_LENGTH = 12;
	static final int STUDIO_RESETS_PER_HOUR = 10;

	/**
	 * O DONO do estúdio gera uma senha TEMPORÁRIA para um tutor que ele já atendeu (ADR 0025): sem e-mail (custo zero), é o jeito de o tutor voltar quando
	 * esquece a senha. A senha é aleatória (12 caracteres sem ambiguidade), aparece UMA vez para o dono (nunca é gravada em claro nem registrada), obriga o tutor
	 * a trocar no próximo acesso, invalida os tokens dele e é auditada (ator = dono, alvo = tutor). Só serve para tutor ATIVO que já teve agendamento NESTE estúdio:
	 * qualquer outro alvo (inexistente, de outro perfil, inativo ou nunca atendido) tem a MESMA resposta de "não encontrado". Limite de 10 por hora por
	 * estúdio, contado antes de qualquer conferência (adivinhar ids de tutor também gasta o limite).
	 */
	@Transactional
	public String resetCustomerPasswordByStudio(UUID ownerUserId, UUID customerId) {
		UserAccount owner = users.findById(ownerUserId).filter(UserAccount::isActive).filter(u -> u.getRole() == com.queuecare.scheduling.persistence.UserRole.PROVIDER)
				.orElseThrow(() -> new org.springframework.security.access.AccessDeniedException("prestador inativo"));
		com.queuecare.scheduling.persistence.Provider provider = providers.findByUserId(owner.getId())
				.orElseThrow(() -> new org.springframework.security.access.AccessDeniedException("sem negócio cadastrado"));
		String key = "studioreset|" + provider.getId();
		Duration window = Duration.ofHours(1);
		if (!limiter.tryAcquire(key, STUDIO_RESETS_PER_HOUR, window)) {
			throw new TooManyLoginAttemptsException(limiter.retryAfterSeconds(key, window));
		}
		UserAccount target;
		try {
			target = lockUser(customerId);
		} catch (InvalidCredentialsException e) {
			throw new TargetUserNotFoundException();
		}
		if (target.getRole() != com.queuecare.scheduling.persistence.UserRole.CUSTOMER || !target.isActive()
				|| !appointments.existsByProviderIdAndCustomerId(provider.getId(), target.getId())) {
			throw new TargetUserNotFoundException();
		}
		String temporary = temporaryPassword();
		target.replacePassword(passwordEncoder.encode(temporary), true);
		users.saveAndFlush(target);
		audit.record(owner.getId(), AuditAction.CUSTOMER_PASSWORD_RESET_BY_STUDIO, target.getId(), target.getRole());
		return temporary;
	}

	private String temporaryPassword() {
		StringBuilder password = new StringBuilder(TEMPORARY_PASSWORD_LENGTH);
		for (int i = 0; i < TEMPORARY_PASSWORD_LENGTH; i++) {
			password.append(TEMPORARY_ALPHABET.charAt(random.nextInt(TEMPORARY_ALPHABET.length())));
		}
		return password.toString();
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
		// Mesma ordem de bloqueios das demais operações de administração (controle, depois usuário) e o ator é reconferido SOB a
		// trava: um ADMIN desativado depois de autenticar não consegue mais redefinir senhas.
		rowLocks.lockAdminControl();
		if (users.findById(adminUserId).filter(UserAccount::isActive).filter(u -> u.getRole() == com.queuecare.scheduling.persistence.UserRole.ADMIN).isEmpty()) {
			throw new org.springframework.security.access.AccessDeniedException("administrador inativo");
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
