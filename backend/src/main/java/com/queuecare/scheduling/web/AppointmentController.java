package com.queuecare.scheduling.web;

import com.queuecare.scheduling.application.AttendanceService;
import com.queuecare.scheduling.application.BookingRequest;
import com.queuecare.scheduling.application.BookingService;
import com.queuecare.scheduling.application.CancellationService;
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
	private final AppointmentRepository appointments;
	private final com.queuecare.scheduling.persistence.ProviderRepository providers;
	private final com.queuecare.scheduling.persistence.ServiceOfferingRepository services;

	AppointmentController(BookingService bookingService, CancellationService cancellationService, AttendanceService attendanceService, AppointmentRepository appointments,
			com.queuecare.scheduling.persistence.ProviderRepository providers, com.queuecare.scheduling.persistence.ServiceOfferingRepository services) {
		this.bookingService = bookingService;
		this.cancellationService = cancellationService;
		this.attendanceService = attendanceService;
		this.appointments = appointments;
		this.providers = providers;
		this.services = services;
	}

	/** {@code localStart} é o horário LOCAL do prestador, ex.: 2026-11-17T10:00:00. */
	record CreateAppointmentRequest(@NotNull UUID providerId, @NotNull UUID serviceId, @NotNull LocalDateTime localStart) {
	}

	/**
	 * Agendamento do próprio cliente, com o nome do negócio, o fuso (para mostrar o horário local) e o nome do serviço. Os nomes
	 * vêm do histórico: continuam aparecendo mesmo se o prestador ou o serviço forem desativados depois.
	 */
	record AppointmentResponse(UUID id, UUID providerId, UUID serviceId, Instant startsAt, Instant endsAt, String status, String businessName,
			String timeZone, String serviceName) {
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
					provider == null ? null : provider.getBusinessName(), provider == null ? null : provider.getTimeZone(), service == null ? null : service.getName());
		}).toList();
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	@PreAuthorize("hasRole('CUSTOMER')")
	AppointmentResponse book(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CreateAppointmentRequest request) {
		Appointment appointment = bookingService
				.book(new BookingRequest(request.providerId(), request.serviceId(), userId(jwt), request.localStart()));
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

	/** Atendimento concluído. Só a equipe do prestador. */
	@PostMapping("/{id}/complete")
	@PreAuthorize("hasAnyRole('PROVIDER','ATTENDANT')")
	AppointmentResponse complete(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
		return respond(List.of(attendanceService.complete(id, userId(jwt)))).get(0);
	}

	private static UUID userId(Jwt jwt) {
		return UUID.fromString(jwt.getSubject());
	}
}
