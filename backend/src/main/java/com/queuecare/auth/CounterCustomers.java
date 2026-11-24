package com.queuecare.auth;

import com.queuecare.audit.AuditAction;
import com.queuecare.audit.AuditService;
import com.queuecare.auth.AuthExceptions.TargetUserNotFoundException;
import com.queuecare.auth.AuthExceptions.TooManyLoginAttemptsException;
import com.queuecare.scheduling.application.StaffAccess;
import com.queuecare.scheduling.persistence.UserAccount;
import com.queuecare.scheduling.persistence.UserAccountRepository;
import com.queuecare.scheduling.persistence.UserRole;
import com.queuecare.security.RateLimiter;
import java.time.Duration;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cadastro de tutor pelo balcão (ADR 0033): a equipe cadastra quem liga ou chama no WhatsApp e agenda por essa pessoa.
 *
 * <p>Duas regras de segurança que não mudam:
 * <ul>
 *   <li><b>Só alcança o que o próprio estúdio criou.</b> A equipe age (cadastra pet, vê horários, reserva) apenas por contas com
 *       {@code created_by_provider_id} igual ao seu estúdio. Conta que o tutor criou sozinho nunca é alcançada: tutor inexistente, de outro
 *       estúdio ou criado pelo próprio tutor têm a MESMA resposta 404, sem pista.</li>
 *   <li><b>A conta nasce sem acesso.</b> A senha é aleatória e ninguém a conhece; sem e-mail informado o e-mail é um endereço reservado
 *       ({@code .invalid}) que não recebe nem permite entrar. Dar acesso ao tutor é outro passo, deliberado (senha temporária do ADR 0025).</li>
 * </ul>
 */
@Service
public class CounterCustomers {

	static final int CREATES_PER_HOUR = 60;

	private final UserAccountRepository users;
	private final StaffAccess staffAccess;
	private final AccountProvisioner provisioner;
	private final PasswordEncoder passwordEncoder;
	private final AuditService audit;
	private final RateLimiter limiter;

	public CounterCustomers(UserAccountRepository users, StaffAccess staffAccess, AccountProvisioner provisioner, PasswordEncoder passwordEncoder, AuditService audit,
			@Qualifier(RateLimiter.LOGIN_LOCKOUT_LIMITER) RateLimiter limiter) {
		this.users = users;
		this.staffAccess = staffAccess;
		this.provisioner = provisioner;
		this.passwordEncoder = passwordEncoder;
		this.audit = audit;
		this.limiter = limiter;
	}

	/** Um tutor do balcão do estúdio {@code providerId}: o que a equipe pode usar para agir por ele. */
	public record Owned(UUID providerId, UserAccount customer) {
	}

	/**
	 * Cadastra o tutor. {@code phone} é obrigatório (é como o estúdio fala com a pessoa); {@code email} é opcional. E-mail que já tem conta na plataforma
	 * dá {@link AccountConflictException} (409 uniforme, que não diz de quem é a conta): o tutor já tem como agendar sozinho.
	 */
	@Transactional
	public UserAccount create(UUID staffUserId, String name, String phone, String email) {
		UUID providerId = staffAccess.providerIdOf(staffUserId).orElseThrow(() -> new AccessDeniedException("sem vínculo com um prestador"));
		Duration window = Duration.ofHours(1);
		String key = "countercreate|" + providerId;
		if (!limiter.tryAcquire(key, CREATES_PER_HOUR, window)) {
			throw new TooManyLoginAttemptsException(limiter.retryAfterSeconds(key, window));
		}
		return createUnlimited(providerId, staffUserId, name, phone, email);
	}

	/**
	 * O mesmo cadastro, SEM o limite por hora: quem chama já aplica o seu (a importação tem o limite por importação, ADR 0034). Participa da transação do chamador, ou
	 * abre a própria quando chamado de fora de uma.
	 */
	@Transactional
	public UserAccount createUnlimited(UUID providerId, UUID staffUserId, String name, String phone, String email) {
		// Telefone é obrigatório no balcão (é como o estúdio fala com a pessoa): vazio é o mesmo erro de formato.
		String normalizedPhone = PhoneNumber.normalize(phone);
		if (normalizedPhone == null) {
			throw new AuthExceptions.InvalidPhoneException();
		}
		boolean hasEmail = email != null && !email.isBlank();
		String address = hasEmail ? AuthService.normalizeEmail(email) : "balcao-" + UUID.randomUUID() + "@balcao.invalid";
		// Senha que ninguém conhece (um UUID: cabe nos 72 bytes do BCrypt). A conta só ganha acesso quando o estúdio gera uma senha temporária.
		UserAccount account = provisioner.provisionCounterCustomer(providerId, name, address, normalizedPhone, passwordEncoder.encode(UUID.randomUUID().toString()));
		audit.record(staffUserId, AuditAction.CUSTOMER_CREATED_BY_STUDIO, account.getId(), account.getRole());
		return account;
	}

	/** Quem a equipe pode usar: tutor ativo cadastrado por ESTE estúdio. Qualquer outro caso é 404 igual, sem pista. */
	@Transactional(readOnly = true)
	public Owned requireOwned(UUID staffUserId, UUID customerId) {
		UUID providerId = staffAccess.providerIdOf(staffUserId).orElseThrow(() -> new AccessDeniedException("sem vínculo com um prestador"));
		UserAccount customer = users.findById(customerId)
				.filter(u -> u.getRole() == UserRole.CUSTOMER && u.isActive() && providerId.equals(u.getCreatedByProviderId()))
				.orElseThrow(TargetUserNotFoundException::new);
		return new Owned(providerId, customer);
	}
}
