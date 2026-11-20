package com.queuecare.scheduling.application;

import com.queuecare.scheduling.application.AppointmentExceptions.AppointmentNotFoundException;
import com.queuecare.scheduling.persistence.Appointment;
import com.queuecare.scheduling.persistence.AppointmentRepository;
import com.queuecare.scheduling.persistence.ServiceOffering;
import com.queuecare.scheduling.persistence.ServiceOfferingRepository;
import com.queuecare.scheduling.persistence.UserAccount;
import com.queuecare.scheduling.persistence.UserAccountRepository;
import com.queuecare.scheduling.persistence.UserRole;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * "Quando foi o último banho?" (ADR 0019, fatia 6): as visitas anteriores CONCLUÍDAS do mesmo pet neste prestador, para a equipe que atende.
 * O prestador vem de {@link StaffAccess} (banco a cada chamada), nunca da requisição; agendamento de outro prestador ou inexistente é 404
 * igual; atendente sem vínculo é 403 antes de consultar o agendamento. Só devolve data, serviço e valor (nada do tutor).
 */
@Service
public class PetVisitsService {

	static final int LIMIT = 10;

	public record Visit(Instant startsAt, String serviceName, Integer priceCents) {
	}

	private final UserAccountRepository users;
	private final AppointmentRepository appointments;
	private final ServiceOfferingRepository services;
	private final StaffAccess staffAccess;

	public PetVisitsService(UserAccountRepository users, AppointmentRepository appointments, ServiceOfferingRepository services, StaffAccess staffAccess) {
		this.users = users;
		this.appointments = appointments;
		this.services = services;
		this.staffAccess = staffAccess;
	}

	@Transactional(readOnly = true)
	public List<Visit> visits(UUID appointmentId, UUID viewerId) {
		UserAccount viewer = users.findById(viewerId).filter(UserAccount::isActive).orElseThrow(() -> new AccessDeniedException("usuário inválido"));
		if (viewer.getRole() != UserRole.PROVIDER && viewer.getRole() != UserRole.ATTENDANT) {
			throw new AccessDeniedException("perfil sem acesso ao atendimento");
		}
		UUID providerId = staffAccess.providerIdOf(viewer).orElseThrow(() -> new AccessDeniedException("sem vínculo com um prestador"));
		Appointment current = appointments.findById(appointmentId).filter(a -> a.getProviderId().equals(providerId)).orElseThrow(AppointmentNotFoundException::new);
		if (current.getPetId() == null) {
			return List.of();
		}
		List<Appointment> before = appointments.findCompletedVisits(current.getPetId(), providerId, current.getStartsAt(), PageRequest.of(0, LIMIT));
		Map<UUID, String> names = services.findAllById(before.stream().map(Appointment::getServiceId).distinct().toList()).stream()
				.collect(Collectors.toMap(ServiceOffering::getId, ServiceOffering::getName));
		return before.stream().map(a -> new Visit(a.getStartsAt(), names.get(a.getServiceId()), a.getPriceCents())).toList();
	}
}
