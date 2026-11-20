package com.queuecare.pets;

import com.queuecare.scheduling.persistence.Appointment;
import com.queuecare.scheduling.persistence.AppointmentRepository;
import com.queuecare.scheduling.persistence.AppointmentStatus;
import com.queuecare.scheduling.persistence.Provider;
import com.queuecare.scheduling.persistence.ProviderRepository;
import com.queuecare.scheduling.persistence.ServiceOffering;
import com.queuecare.scheduling.persistence.ServiceOfferingRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Histórico de UM pet para o próprio tutor (ADR 0019, fatia 6): as visitas, todas as situações, da mais recente para a mais antiga. O dono vem
 * SEMPRE do usuário autenticado; pet de outro cliente é indistinguível de pet inexistente (404). Só leitura: não bloqueia linhas.
 */
@Service
public class PetHistoryService {

	static final int LIMIT = 50;

	/** Uma visita: o agendamento com os nomes do negócio e do serviço (que continuam aparecendo mesmo se forem desativados depois). */
	public record Visit(UUID id, Instant startsAt, Instant endsAt, AppointmentStatus status, UUID providerId, String businessName, String timeZone, UUID serviceId,
			String serviceName, Integer priceCents) {
	}

	private final PetService pets;
	private final AppointmentRepository appointments;
	private final ProviderRepository providers;
	private final ServiceOfferingRepository services;

	public PetHistoryService(PetService pets, AppointmentRepository appointments, ProviderRepository providers, ServiceOfferingRepository services) {
		this.pets = pets;
		this.appointments = appointments;
		this.providers = providers;
		this.services = services;
	}

	@Transactional(readOnly = true)
	public List<Visit> visits(UUID customerId, UUID petId) {
		pets.get(customerId, petId); // confere o dono (pet alheio = 404); o único acesso ao repositório de pets continua no PetService
		List<Appointment> found = appointments.findPetVisits(petId, customerId, PageRequest.of(0, LIMIT));
		Map<UUID, Provider> byProvider = providers.findAllById(found.stream().map(Appointment::getProviderId).distinct().toList()).stream()
				.collect(Collectors.toMap(Provider::getId, p -> p));
		Map<UUID, ServiceOffering> byService = services.findAllById(found.stream().map(Appointment::getServiceId).distinct().toList()).stream()
				.collect(Collectors.toMap(ServiceOffering::getId, s -> s));
		return found.stream().map(a -> {
			Provider provider = byProvider.get(a.getProviderId());
			ServiceOffering service = byService.get(a.getServiceId());
			return new Visit(a.getId(), a.getStartsAt(), a.getEndsAt(), a.getStatus(), a.getProviderId(), provider == null ? null : provider.getBusinessName(),
					provider == null ? null : provider.getTimeZone(), a.getServiceId(), service == null ? null : service.getName(), a.getPriceCents());
		}).toList();
	}
}
