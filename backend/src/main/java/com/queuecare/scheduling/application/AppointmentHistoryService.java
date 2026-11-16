package com.queuecare.scheduling.application;

import com.queuecare.scheduling.application.AppointmentExceptions.AppointmentNotFoundException;
import com.queuecare.scheduling.persistence.Appointment;
import com.queuecare.scheduling.persistence.AppointmentEvent;
import com.queuecare.scheduling.persistence.AppointmentEventRepository;
import com.queuecare.scheduling.persistence.AppointmentEventType;
import com.queuecare.scheduling.persistence.AppointmentRepository;
import com.queuecare.scheduling.persistence.UserAccount;
import com.queuecare.scheduling.persistence.UserAccountRepository;
import com.queuecare.scheduling.persistence.UserRole;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Histórico do agendamento, CONSULTÁVEL (ADR 0017, item 9): a auditoria que torna aceitável a equipe poder remarcar sem limite. Somente
 * leitura. Quem vê: o CLIENTE dono e a EQUIPE do prestador (mesmas regras do cancelamento: agendamento alheio ou inexistente = a mesma 404;
 * atendente sem vínculo = 403 antes de qualquer consulta). A equipe vê o NOME de quem agiu; o cliente vê só se foi "o cliente" ou "a equipe"
 * (nunca o nome de um funcionário). Ordem determinística: instante e, em empate, id.
 */
@Service
public class AppointmentHistoryService {

	/** Quem agiu, sem identificar pessoas da equipe para o cliente. */
	public enum Actor {
		CUSTOMER, STAFF, SYSTEM
	}

	/** Uma linha do histórico. {@code actorName} só vem para a equipe (nulo para o cliente e nos eventos do sistema). */
	public record Entry(AppointmentEventType type, Instant at, Actor by, String actorName, Instant previousStartsAt, Instant newStartsAt) {
	}

	private final UserAccountRepository users;
	private final AppointmentRepository appointments;
	private final AppointmentEventRepository events;
	private final StaffAccess staffAccess;

	public AppointmentHistoryService(UserAccountRepository users, AppointmentRepository appointments, AppointmentEventRepository events, StaffAccess staffAccess) {
		this.users = users;
		this.appointments = appointments;
		this.events = events;
		this.staffAccess = staffAccess;
	}

	@Transactional(readOnly = true)
	public List<Entry> history(UUID appointmentId, UUID viewerId) {
		UserAccount viewer = users.findById(viewerId).filter(UserAccount::isActive).orElseThrow(AppointmentNotFoundException::new);
		boolean isStaff = viewer.getRole() == UserRole.PROVIDER || viewer.getRole() == UserRole.ATTENDANT;
		Optional<UUID> staffProviderId = isStaff ? staffAccess.providerIdOf(viewer) : Optional.empty();
		if (viewer.getRole() == UserRole.ATTENDANT && staffProviderId.isEmpty()) {
			throw new AccessDeniedException("atendente sem vínculo com um prestador");
		}
		Appointment appointment = appointments.findById(appointmentId).orElseThrow(AppointmentNotFoundException::new);
		boolean ownerCustomer = viewer.getRole() == UserRole.CUSTOMER && appointment.getCustomerId().equals(viewer.getId());
		boolean staffOfProvider = staffProviderId.filter(appointment.getProviderId()::equals).isPresent();
		if (!ownerCustomer && !staffOfProvider) {
			throw new AppointmentNotFoundException();
		}

		List<AppointmentEvent> list = events.findByAppointmentIdOrderByCreatedAtAscIdAsc(appointmentId);
		Map<UUID, UserAccount> actors = users.findAllById(list.stream().map(AppointmentEvent::getActorId).filter(java.util.Objects::nonNull).distinct().toList()).stream()
				.collect(Collectors.toMap(UserAccount::getId, Function.identity()));
		return list.stream().map(e -> {
			UserAccount actor = e.getActorId() == null ? null : actors.get(e.getActorId());
			Actor by = actor == null ? Actor.SYSTEM : (actor.getRole() == UserRole.CUSTOMER ? Actor.CUSTOMER : Actor.STAFF);
			String name = staffOfProvider && actor != null ? actor.getName() : null;
			return new Entry(e.getEventType(), e.getCreatedAt(), by, name, e.getPreviousStartsAt(), e.getNewStartsAt());
		}).toList();
	}
}
