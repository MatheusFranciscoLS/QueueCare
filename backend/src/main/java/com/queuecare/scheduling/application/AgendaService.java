package com.queuecare.scheduling.application;

import com.queuecare.scheduling.application.ProviderExceptions.ProviderNotConfiguredException;
import com.queuecare.scheduling.persistence.Appointment;
import com.queuecare.scheduling.persistence.AppointmentRepository;
import com.queuecare.scheduling.persistence.AppointmentStatus;
import com.queuecare.scheduling.persistence.Provider;
import com.queuecare.scheduling.persistence.ProviderRepository;
import com.queuecare.scheduling.persistence.ServiceOffering;
import com.queuecare.scheduling.persistence.ServiceOfferingRepository;
import com.queuecare.scheduling.persistence.UserAccount;
import com.queuecare.scheduling.persistence.UserAccountRepository;
import com.queuecare.scheduling.persistence.UserRole;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Agenda do dia do prestador, para o PROVIDER dono e para o ATTENDANT vinculado (ADR 0004). O prestador vem SEMPRE de
 * {@link StaffAccess}; não existe parâmetro de prestador. A data é a data LOCAL do prestador: o dia é o intervalo de
 * instantes [início do dia, início do dia seguinte) no fuso IANA dele (correto em dias de horário de verão).
 * Mostra só agendamentos NÃO cancelados e, do cliente, apenas id e nome (sem e-mail nem outros dados pessoais).
 */
@Service
public class AgendaService {

	private final UserAccountRepository users;
	private final ProviderRepository providers;
	private final ServiceOfferingRepository services;
	private final AppointmentRepository appointments;
	private final StaffAccess staffAccess;

	public AgendaService(UserAccountRepository users, ProviderRepository providers, ServiceOfferingRepository services,
			AppointmentRepository appointments, StaffAccess staffAccess) {
		this.users = users;
		this.providers = providers;
		this.services = services;
		this.appointments = appointments;
		this.staffAccess = staffAccess;
	}

	/** Um agendamento da agenda. */
	public record AgendaItem(UUID id, Instant startsAt, Instant endsAt, AppointmentStatus status, UUID serviceId, String serviceName,
			UUID customerId, String customerName) {
	}

	/** Onde a pessoa trabalha: o negócio e o fuso em que os horários da agenda valem (o atendente não tem acesso a /api/provider). */
	public record Workplace(String businessName, String timeZone) {
	}

	@Transactional(readOnly = true)
	public Workplace workplace(UUID userId) {
		Provider provider = resolveProvider(userId);
		return new Workplace(provider.getBusinessName(), provider.getTimeZone());
	}

	/** O prestador em que o usuário opera, com as MESMAS regras da agenda (perfil, vínculo e dono ativo); nunca vem da requisição. */
	private Provider resolveProvider(UUID userId) {
		UserAccount actor = users.findById(userId).filter(UserAccount::isActive).orElseThrow(() -> new AccessDeniedException("usuário inválido"));
		if (actor.getRole() != UserRole.PROVIDER && actor.getRole() != UserRole.ATTENDANT) {
			throw new AccessDeniedException("perfil sem acesso à agenda");
		}
		UUID providerId = staffAccess.providerIdOf(actor).orElseThrow(() -> actor.getRole() == UserRole.PROVIDER
				? new ProviderNotConfiguredException()
				: new AccessDeniedException("atendente sem vínculo com um prestador"));
		return providers.findById(providerId).orElseThrow(ProviderNotConfiguredException::new);
	}

	@Transactional(readOnly = true)
	public List<AgendaItem> day(UUID userId, LocalDate date) {
		Provider provider = resolveProvider(userId);
		UUID providerId = provider.getId();

		ZoneId zone = ZoneId.of(provider.getTimeZone());
		Instant dayStart = date.atStartOfDay(zone).toInstant();
		Instant dayEnd = date.plusDays(1).atStartOfDay(zone).toInstant();
		List<Appointment> day = appointments.findDayAgenda(providerId, dayStart, dayEnd);

		Map<UUID, String> serviceNames = services.findAllById(day.stream().map(Appointment::getServiceId).distinct().toList()).stream()
				.collect(Collectors.toMap(ServiceOffering::getId, ServiceOffering::getName));
		Map<UUID, String> customerNames = users.findAllById(day.stream().map(Appointment::getCustomerId).distinct().toList()).stream()
				.collect(Collectors.toMap(UserAccount::getId, UserAccount::getName, (a, b) -> a, java.util.HashMap::new));
		return day.stream().map(a -> new AgendaItem(a.getId(), a.getStartsAt(), a.getEndsAt(), a.getStatus(), a.getServiceId(),
				serviceNames.get(a.getServiceId()), a.getCustomerId(), customerNames.get(a.getCustomerId()))).toList();
	}

}
