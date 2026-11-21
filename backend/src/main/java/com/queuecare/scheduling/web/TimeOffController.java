package com.queuecare.scheduling.web;

import com.queuecare.scheduling.application.TimeOffService;
import com.queuecare.scheduling.persistence.TimeOff;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Folgas, feriados e férias do PRÓPRIO negócio (ADR 0024). Só o PROVIDER; o negócio é o do token, nunca vem do pedido. */
@RestController
@RequestMapping("/api/provider/time-off")
@PreAuthorize("hasRole('PROVIDER')")
class TimeOffController {

	private final TimeOffService service;

	TimeOffController(TimeOffService service) {
		this.service = service;
	}

	record TimeOffRequest(@NotNull LocalDate startsOn, @NotNull LocalDate endsOn, String reason) {
	}

	record TimeOffResponse(UUID id, LocalDate startsOn, LocalDate endsOn, String reason) {
		static TimeOffResponse from(TimeOff t) {
			return new TimeOffResponse(t.getId(), t.getStartsOn(), t.getEndsOn(), t.getReason());
		}
	}

	/** {@code affectedAppointments}: agendamentos ativos que já existiam no período (nada é cancelado sozinho). */
	record CreatedResponse(UUID id, LocalDate startsOn, LocalDate endsOn, String reason, long affectedAppointments) {
	}

	@GetMapping
	List<TimeOffResponse> list(@AuthenticationPrincipal Jwt jwt) {
		return service.upcoming(UUID.fromString(jwt.getSubject())).stream().map(TimeOffResponse::from).toList();
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	CreatedResponse create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody TimeOffRequest r) {
		TimeOffService.Created created = service.create(UUID.fromString(jwt.getSubject()), r.startsOn(), r.endsOn(), r.reason());
		TimeOff t = created.period();
		return new CreatedResponse(t.getId(), t.getStartsOn(), t.getEndsOn(), t.getReason(), created.affectedAppointments());
	}

	@DeleteMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void delete(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
		service.delete(UUID.fromString(jwt.getSubject()), id);
	}
}
