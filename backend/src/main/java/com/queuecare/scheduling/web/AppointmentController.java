package com.queuecare.scheduling.web;

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
	private final AppointmentRepository appointments;

	AppointmentController(BookingService bookingService, CancellationService cancellationService, AppointmentRepository appointments) {
		this.bookingService = bookingService;
		this.cancellationService = cancellationService;
		this.appointments = appointments;
	}

	/** {@code localStart} é o horário LOCAL do prestador, ex.: 2026-11-17T10:00:00. */
	record CreateAppointmentRequest(@NotNull UUID providerId, @NotNull UUID serviceId, @NotNull LocalDateTime localStart) {
	}

	record AppointmentResponse(UUID id, UUID providerId, UUID serviceId, Instant startsAt, Instant endsAt, String status) {
		static AppointmentResponse from(Appointment a) {
			return new AppointmentResponse(a.getId(), a.getProviderId(), a.getServiceId(), a.getStartsAt(), a.getEndsAt(), a.getStatus().name());
		}
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	@PreAuthorize("hasRole('CUSTOMER')")
	AppointmentResponse book(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CreateAppointmentRequest request) {
		Appointment appointment = bookingService
				.book(new BookingRequest(request.providerId(), request.serviceId(), userId(jwt), request.localStart()));
		return AppointmentResponse.from(appointment);
	}

	@GetMapping("/mine")
	@PreAuthorize("hasRole('CUSTOMER')")
	List<AppointmentResponse> mine(@AuthenticationPrincipal Jwt jwt, @RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "20") int size) {
		if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "paginação inválida");
		}
		PageRequest pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "startsAt"));
		return appointments.findByCustomerId(userId(jwt), pageable).stream().map(AppointmentResponse::from).toList();
	}

	@PostMapping("/{id}/cancel")
	AppointmentResponse cancel(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
		return AppointmentResponse.from(cancellationService.cancel(id, userId(jwt)));
	}

	private static UUID userId(Jwt jwt) {
		return UUID.fromString(jwt.getSubject());
	}
}
