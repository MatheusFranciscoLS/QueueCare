package com.queuecare.auth;

import com.queuecare.audit.AuditAction;
import com.queuecare.audit.AuditService;
import com.queuecare.auth.AuthExceptions.InvalidCredentialsException;
import com.queuecare.auth.AuthExceptions.InvalidCurrentPasswordException;
import com.queuecare.auth.AuthExceptions.TooManyLoginAttemptsException;
import com.queuecare.pets.Pet;
import com.queuecare.pets.PetService;
import com.queuecare.scheduling.persistence.Appointment;
import com.queuecare.scheduling.persistence.AppointmentEvent;
import com.queuecare.scheduling.persistence.AppointmentEventRepository;
import com.queuecare.scheduling.persistence.AppointmentEventType;
import com.queuecare.scheduling.persistence.AppointmentRepository;
import com.queuecare.scheduling.persistence.Provider;
import com.queuecare.scheduling.persistence.ProviderRepository;
import com.queuecare.scheduling.persistence.ServiceOffering;
import com.queuecare.scheduling.persistence.ServiceOfferingRepository;
import com.queuecare.scheduling.persistence.UserAccount;
import com.queuecare.scheduling.persistence.UserAccountRepository;
import com.queuecare.scheduling.persistence.UserRole;
import com.queuecare.scheduling.persistence.WaitlistEntry;
import com.queuecare.scheduling.persistence.WaitlistEntryRepository;
import com.queuecare.security.RateLimitProperties;
import com.queuecare.security.RateLimiter;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Os direitos do tutor sobre os PRÓPRIOS dados (ADR 0027, LGPD mínima): exportar tudo o que o app guarda dele e excluir a conta.
 *
 * <p>Excluir é ANONIMIZAR no lugar, não apagar a linha: agendamentos (do estúdio) e auditoria apontam para ela. Saem o nome, o e-mail, o telefone, a senha e os
 * dados dos pets (inclusive a cópia gravada em cada agendamento); ficam horário, serviço e valor, que são registro operacional do estúdio. Os agendamentos que
 * ainda estavam marcados são cancelados (o estúdio vê o horário liberado) e a lista de espera é esvaziada. Exige a senha atual, com limite de tentativas.
 * Só o tutor tem esses dois caminhos: dono, atendente e administrador têm conta de trabalho, tratada pelo administrador (ADR 0005 e 0007).
 */
@Service
public class PrivacyService {

	/** Uma exclusão por tentativa de senha: 5 erros e a conta fica bloqueada para excluir por uma janela (a mesma da troca de senha). */
	static final int DELETE_ATTEMPTS = 5;
	private static final int MAX_EXPORT_APPOINTMENTS = 5_000;

	private final UserAccountRepository users;
	private final PasswordEncoder passwordEncoder;
	private final AuditService audit;
	private final RateLimiter limiter;
	private final RateLimitProperties limits;
	private final RowLocks rowLocks;
	private final PetService pets;
	private final AppointmentRepository appointments;
	private final AppointmentEventRepository events;
	private final WaitlistEntryRepository waitlist;
	private final ProviderRepository providers;
	private final ServiceOfferingRepository services;
	private final Clock clock;
	private final CounterCustomers counter;

	public PrivacyService(UserAccountRepository users, PasswordEncoder passwordEncoder, AuditService audit,
			@Qualifier(RateLimiter.LOGIN_LOCKOUT_LIMITER) RateLimiter limiter, RateLimitProperties limits, RowLocks rowLocks, PetService pets,
			AppointmentRepository appointments, AppointmentEventRepository events, WaitlistEntryRepository waitlist, ProviderRepository providers,
			ServiceOfferingRepository services, Clock clock, CounterCustomers counter) {
		this.counter = counter;
		this.users = users;
		this.passwordEncoder = passwordEncoder;
		this.audit = audit;
		this.limiter = limiter;
		this.limits = limits;
		this.rowLocks = rowLocks;
		this.pets = pets;
		this.appointments = appointments;
		this.events = events;
		this.waitlist = waitlist;
		this.providers = providers;
		this.services = services;
		this.clock = clock;
	}

	public record ExportedAccount(String name, String email, String phone, Instant createdAt, String privacyPolicyVersion, Instant privacyAcceptedAt) {
	}

	public record ExportedPet(String name, String species, String breed, String size, String coat, java.math.BigDecimal weightKg, String temperament, String healthNotes,
			String notes, Boolean vaccinated, boolean active, Instant createdAt) {
	}

	public record ExportedAppointment(String studio, String service, Instant startsAt, Instant endsAt, String status, Integer priceCents, String petName) {
	}

	public record ExportedWaitlist(LocalDate wantedDate, String studio, String service, Instant createdAt) {
	}

	/** Tudo o que o app guarda do tutor, num pacote só (a LGPD chama de acesso e portabilidade). */
	public record Export(Instant generatedAt, ExportedAccount account, List<ExportedPet> pets, List<ExportedAppointment> appointments, List<ExportedWaitlist> waitlist) {
	}

	@Transactional(readOnly = true)
	public Export export(UUID userId) {
		UserAccount user = activeCustomer(userId);
		List<Appointment> mine = appointments.findByCustomerId(userId, PageRequest.of(0, MAX_EXPORT_APPOINTMENTS, Sort.by(Sort.Order.desc("startsAt"), Sort.Order.desc("id"))));
		Map<UUID, Provider> studios = providers.findAllById(mine.stream().map(Appointment::getProviderId).distinct().toList()).stream()
				.collect(Collectors.toMap(Provider::getId, Function.identity()));
		Map<UUID, ServiceOffering> offerings = services.findAllById(mine.stream().map(Appointment::getServiceId).distinct().toList()).stream()
				.collect(Collectors.toMap(ServiceOffering::getId, Function.identity()));

		List<ExportedPet> exportedPets = pets.list(userId, true).stream().map(PrivacyService::toExported).toList();
		List<ExportedAppointment> exportedAppointments = mine.stream().map(a -> new ExportedAppointment(nameOf(studios.get(a.getProviderId())),
				a.getServiceId() == null || offerings.get(a.getServiceId()) == null ? null : offerings.get(a.getServiceId()).getName(), a.getStartsAt(), a.getEndsAt(),
				a.getStatus().name(), a.getPriceCents(), a.getPetName())).toList();
		List<WaitlistEntry> entries = waitlist.findActiveOfCustomer(userId, LocalDate.of(2000, 1, 1));
		Map<UUID, Provider> waitStudios = providers.findAllById(entries.stream().map(WaitlistEntry::getProviderId).distinct().toList()).stream()
				.collect(Collectors.toMap(Provider::getId, Function.identity()));
		Map<UUID, ServiceOffering> waitServices = services.findAllById(entries.stream().map(WaitlistEntry::getServiceId).distinct().toList()).stream()
				.collect(Collectors.toMap(ServiceOffering::getId, Function.identity()));
		List<ExportedWaitlist> exportedWaitlist = entries.stream().map(w -> new ExportedWaitlist(w.getWantedDate(), nameOf(waitStudios.get(w.getProviderId())),
				waitServices.get(w.getServiceId()) == null ? null : waitServices.get(w.getServiceId()).getName(), w.getCreatedAt())).toList();

		ExportedAccount account = new ExportedAccount(user.getName(), user.getEmail(), user.getPhone(), user.getCreatedAt(), user.getPrivacyPolicyVersion(), user.getPrivacyAcceptedAt());
		return new Export(clock.instant(), account, exportedPets, exportedAppointments, exportedWaitlist);
	}

	/**
	 * Exclui a conta (anonimiza no lugar). A senha atual é conferida com limite de tentativas (reserva atômica antes de comparar), com a linha do usuário
	 * bloqueada: duas exclusões simultâneas acontecem uma de cada vez e a segunda já encontra a conta inativa.
	 */
	@Transactional
	public void deleteAccount(UUID userId, String currentPassword) {
		String attemptKey = "delaccount|" + userId;
		Duration window = Duration.ofMinutes(limits.loginFailureWindowMinutes());
		if (!limiter.tryAcquire(attemptKey, DELETE_ATTEMPTS, window)) {
			throw new TooManyLoginAttemptsException(limiter.retryAfterSeconds(attemptKey, window));
		}
		UserAccount user = rowLocks.lockUser(userId).orElseThrow(InvalidCredentialsException::new);
		if (!user.isActive() || user.getRole() != UserRole.CUSTOMER || currentPassword == null || !passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
			throw new InvalidCurrentPasswordException();
		}
		anonymizeAccount(user, userId, "conta excluída pelo tutor", AuditAction.ACCOUNT_ANONYMIZED);
		limiter.clear(attemptKey);
	}


	/**
	 * Os dados de um tutor cadastrado pelo balcão, para o DONO entregar a ele (ADR 0033): o tutor sem acesso ao app não consegue baixar sozinho. Só de conta que o
	 * próprio estúdio criou (404 igual para qualquer outro alvo), e o pedido fica na auditoria (são dados pessoais saindo).
	 */
	@Transactional
	public Export exportForStudio(UUID ownerUserId, UUID customerId) {
		UserAccount customer = counter.requireOwned(ownerUserId, customerId).customer();
		audit.record(ownerUserId, AuditAction.CUSTOMER_EXPORTED_BY_STUDIO, customer.getId(), UserRole.CUSTOMER);
		return export(customer.getId());
	}

	/**
	 * Exclui (anonimiza) a conta de um tutor do balcão a pedido dele (ADR 0033). Só o dono, só de conta criada pelo estúdio, e SÓ enquanto o tutor nunca assumiu a conta
	 * ({@code must_change_password} ainda verdadeiro): quem já entrou no app tem a própria exclusão, com a própria senha, e o estúdio não apaga a conta de quem a usa.
	 * Mesmos efeitos da exclusão feita pelo tutor: horários marcados voltam, lista de espera sai, pets e cópias nos agendamentos são anonimizados.
	 */
	@Transactional
	public void anonymizeForStudio(UUID ownerUserId, UUID customerId) {
		counter.requireOwned(ownerUserId, customerId);
		UserAccount user = rowLocks.lockUser(customerId).orElseThrow(AuthExceptions.TargetUserNotFoundException::new);
		if (!user.isActive() || user.getRole() != UserRole.CUSTOMER) {
			throw new AuthExceptions.TargetUserNotFoundException();
		}
		if (!user.isMustChangePassword()) {
			throw new AuthExceptions.CustomerHasAccessException();
		}
		anonymizeAccount(user, ownerUserId, "conta excluída a pedido do tutor, pelo estúdio", AuditAction.CUSTOMER_ANONYMIZED_BY_STUDIO);
	}

	/** O núcleo comum das duas exclusões; {@code actorId} é quem agiu (o tutor, ou o dono do estúdio) e vai no histórico e na auditoria. */
	private void anonymizeAccount(UserAccount user, UUID actorId, String cancelNote, AuditAction auditAction) {
		UUID userId = user.getId();
		Instant now = clock.instant();

		// Os horários que ainda estavam marcados voltam para o estúdio (com o evento de cancelamento no histórico).
		for (Appointment appointment : appointments.findFutureScheduledOf(userId, now)) {
			appointment.cancel();
			appointments.saveAndFlush(appointment);
			events.save(new AppointmentEvent(appointment.getId(), actorId, AppointmentEventType.CANCELLED, now, cancelNote));
		}
		for (WaitlistEntry entry : waitlist.findActiveOfCustomer(userId, LocalDate.of(2000, 1, 1))) {
			entry.remove(now);
			waitlist.save(entry);
		}
		pets.anonymizeAll(userId);
		appointments.anonymizePetCopies(userId);

		user.anonymize(passwordEncoder.encode(UUID.randomUUID().toString()), now);
		audit.record(actorId, auditAction, userId, UserRole.CUSTOMER);
	}

	private UserAccount activeCustomer(UUID userId) {
		return users.findById(userId).filter(UserAccount::isActive).filter(u -> u.getRole() == UserRole.CUSTOMER).orElseThrow(() -> new AccessDeniedException("somente o tutor"));
	}

	private static String nameOf(Provider provider) {
		return provider == null ? null : provider.getBusinessName();
	}

	private static ExportedPet toExported(Pet p) {
		return new ExportedPet(p.getName(), p.getSpecies() == null ? null : p.getSpecies().name(), p.getBreed(), p.getSize() == null ? null : p.getSize().name(),
				p.getCoat() == null ? null : p.getCoat().name(), p.getWeightKg(), p.getTemperament() == null ? null : p.getTemperament().name(), p.getHealthNotes(), p.getNotes(),
				p.getVaccinated(), p.isActive(), p.getCreatedAt());
	}
}
