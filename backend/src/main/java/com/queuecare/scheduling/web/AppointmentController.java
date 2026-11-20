package com.queuecare.scheduling.web;

import com.queuecare.scheduling.application.AppointmentHistoryService;
import com.queuecare.scheduling.application.AttendanceService;
import com.queuecare.scheduling.application.BookingRequest;
import com.queuecare.scheduling.application.BookingService;
import com.queuecare.scheduling.application.CancellationService;
import com.queuecare.scheduling.application.RescheduleService;
import com.queuecare.scheduling.persistence.Appointment;
import com.queuecare.scheduling.persistence.AppointmentRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * API de agendamento. A identidade do cliente vem SEMPRE do token (principal autenticado); nenhum campo do corpo da
 * requisição define quem está agendando ou cancelando (campos extras como "customerId" são ignorados).
 */
@RestController
@RequestMapping("/api/appointments")
class AppointmentController {

	/** Limite de itens por página em /mine. */
	static final int MAX_PAGE_SIZE = 50;

	private final BookingService bookingService;
	private final CancellationService cancellationService;
	private final AttendanceService attendanceService;
	private final RescheduleService rescheduleService;
	private final AppointmentHistoryService historyService;
	private final com.queuecare.scheduling.application.PetVisitsService petVisitsService;
	private final AppointmentRepository appointments;
	private final com.queuecare.scheduling.persistence.ProviderRepository providers;
	private final com.queuecare.scheduling.persistence.ServiceOfferingRepository services;

	AppointmentController(BookingService bookingService, CancellationService cancellationService, AttendanceService attendanceService, RescheduleService rescheduleService,
			AppointmentHistoryService historyService, com.queuecare.scheduling.application.PetVisitsService petVisitsService, AppointmentRepository appointments,
			com.queuecare.scheduling.persistence.ProviderRepository providers, com.queuecare.scheduling.persistence.ServiceOfferingRepository services) {
		this.bookingService = bookingService;
		this.cancellationService = cancellationService;
		this.attendanceService = attendanceService;
		this.rescheduleService = rescheduleService;
		this.historyService = historyService;
		this.petVisitsService = petVisitsService;
		this.appointments = appointments;
		this.providers = providers;
		this.services = services;
	}

	/** {@code localStart} é o horário LOCAL do prestador, ex.: 2026-11-17T10:00:00. */
	record CreateAppointmentRequest(@NotNull UUID providerId, @NotNull UUID serviceId, @NotNull LocalDateTime localStart,
			@io.swagger.v3.oas.annotations.media.Schema(requiredMode = io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED) UUID petId) {
	}

	/**
	 * Agendamento do próprio cliente, com o nome do negócio, o fuso (para mostrar o horário local) e o nome do serviço. Os nomes
	 * vêm do histórico: continuam aparecendo mesmo se o prestador ou o serviço forem desativados depois.
	 */
	record AppointmentResponse(UUID id, UUID providerId, UUID serviceId, Instant startsAt, Instant endsAt, String status, String businessName,
			String timeZone, String serviceName, Integer priceCents, AppointmentPetResponse pet) {
	}

	/** Pet do agendamento (cópia gravada nele): só nome e espécie; ausente (nulo) em agendamento sem pet. */
	record AppointmentPetResponse(String name, String species) {
	}

	private List<AppointmentResponse> respond(List<Appointment> list) {
		java.util.Map<UUID, com.queuecare.scheduling.persistence.Provider> byProvider = providers
				.findAllById(list.stream().map(Appointment::getProviderId).distinct().toList()).stream()
				.collect(java.util.stream.Collectors.toMap(com.queuecare.scheduling.persistence.Provider::getId, p -> p));
		java.util.Map<UUID, com.queuecare.scheduling.persistence.ServiceOffering> byService = services
				.findAllById(list.stream().map(Appointment::getServiceId).distinct().toList()).stream()
				.collect(java.util.stream.Collectors.toMap(com.queuecare.scheduling.persistence.ServiceOffering::getId, x -> x));
		return list.stream().map(a -> {
			var provider = byProvider.get(a.getProviderId());
			var service = byService.get(a.getServiceId());
			return new AppointmentResponse(a.getId(), a.getProviderId(), a.getServiceId(), a.getStartsAt(), a.getEndsAt(), a.getStatus().name(),
					provider == null ? null : provider.getBusinessName(), provider == null ? null : provider.getTimeZone(), service == null ? null : service.getName(), a.getPriceCents(),
					a.getPetId() == null ? null : new AppointmentPetResponse(a.getPetName(), a.getPetSpecies().name()));
		}).toList();
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	@PreAuthorize("hasRole('CUSTOMER')")
	AppointmentResponse book(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CreateAppointmentRequest request) {
		Appointment appointment = bookingService
				.book(new BookingRequest(request.providerId(), request.serviceId(), userId(jwt), request.localStart(), request.petId()));
		return respond(List.of(appointment)).get(0);
	}

	@GetMapping("/mine")
	@PreAuthorize("hasRole('CUSTOMER')")
	List<AppointmentResponse> mine(@AuthenticationPrincipal Jwt jwt, @RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "20") int size) {
		if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "paginação inválida");
		}
		PageRequest pageable = PageRequest.of(page, size, Sort.by(Sort.Order.desc("startsAt"), Sort.Order.desc("id")));
		return respond(appointments.findByCustomerId(userId(jwt), pageable).stream().toList());
	}

	/** {@code localStart} é o NOVO horário LOCAL do prestador, como na reserva (ADR 0017). */
	record RescheduleRequest(@NotNull LocalDateTime localStart) {
	}

	/**
	 * Uma linha do histórico. {@code by}: CUSTOMER, STAFF ou SYSTEM. {@code actorName} só vem para a equipe do prestador (nulo para o cliente).
	 * {@code previousStartsAt} e {@code newStartsAt} só nas remarcações (RESCHEDULED).
	 */
	record HistoryEntryResponse(String type, Instant at, String by, String actorName, Instant previousStartsAt, Instant newStartsAt) {
	}

	/**
	 * Remarcar: move o MESMO agendamento para outro horário (mesma duração, mesmo pet). Cliente dono (no prazo, até 3 vezes) e equipe do
	 * prestador (sem prazo e sem limite). Mesmas regras de horário da reserva.
	 */
	@PostMapping("/{id}/reschedule")
	@PreAuthorize("hasAnyRole('CUSTOMER','PROVIDER','ATTENDANT')")
	AppointmentResponse reschedule(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody RescheduleRequest request) {
		return respond(List.of(rescheduleService.reschedule(id, userId(jwt), request.localStart()))).get(0);
	}

	/** Histórico do agendamento (somente leitura): o cliente dono e a equipe do prestador; só a equipe vê o nome de quem agiu. */
	@GetMapping("/{id}/events")
	@PreAuthorize("hasAnyRole('CUSTOMER','PROVIDER','ATTENDANT')")
	List<HistoryEntryResponse> events(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
		return historyService.history(id, userId(jwt)).stream()
				.map(e -> new HistoryEntryResponse(e.type().name(), e.at(), e.by().name(), e.actorName(), e.previousStartsAt(), e.newStartsAt())).toList();
	}

	/** Uma visita anterior do pet neste prestador (ADR 0019, fatia 6). */
	record PetVisitResponse(Instant startsAt, String serviceName, Integer priceCents) {
	}

	/**
	 * "Quando foi o último banho?" (ADR 0019, fatia 6): atendimentos CONCLUÍDOS anteriores do MESMO pet NESTE prestador, o mais recente primeiro (até 10).
	 * Só a equipe do prestador do agendamento; outro prestador ou agendamento inexistente: 404. Agendamento sem pet: lista vazia.
	 */
	@GetMapping("/{id}/pet-visits")
	@PreAuthorize("hasAnyRole('PROVIDER','ATTENDANT')")
	List<PetVisitResponse> petVisits(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
		return petVisitsService.visits(id, userId(jwt)).stream().map(v -> new PetVisitResponse(v.startsAt(), v.serviceName(), v.priceCents())).toList();
	}

	@PostMapping("/{id}/cancel")
	@PreAuthorize("hasAnyRole('CUSTOMER','PROVIDER','ATTENDANT')")
	AppointmentResponse cancel(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
		return respond(List.of(cancellationService.cancel(id, userId(jwt)))).get(0);
	}

	/** A pessoa chegou. Só a equipe do prestador; o prestador vem do usuário autenticado. */
	@PostMapping("/{id}/check-in")
	@PreAuthorize("hasAnyRole('PROVIDER','ATTENDANT')")
	AppointmentResponse checkIn(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
		return respond(List.of(attendanceService.checkIn(id, userId(jwt)))).get(0);
	}

	/** O atendimento começou (de quem chegou). Só a equipe do prestador. */
	@PostMapping("/{id}/start")
	@PreAuthorize("hasAnyRole('PROVIDER','ATTENDANT')")
	AppointmentResponse start(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
		return respond(List.of(attendanceService.start(id, userId(jwt)))).get(0);
	}

	/** O pet está pronto para retirada (de quem chegou ou está em atendimento). Só a equipe do prestador. */
	@PostMapping("/{id}/ready")
	@PreAuthorize("hasAnyRole('PROVIDER','ATTENDANT')")
	AppointmentResponse ready(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
		return respond(List.of(attendanceService.ready(id, userId(jwt)))).get(0);
	}

	/** Atendimento concluído (pet retirado). Só a equipe do prestador. */
	@PostMapping("/{id}/complete")
	@PreAuthorize("hasAnyRole('PROVIDER','ATTENDANT')")
	AppointmentResponse complete(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
		return respond(List.of(attendanceService.complete(id, userId(jwt)))).get(0);
	}

	private static UUID userId(Jwt jwt) {
		return UUID.fromString(jwt.getSubject());
	}
}
