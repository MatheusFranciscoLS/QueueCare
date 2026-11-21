package com.queuecare.scheduling.web;

import com.queuecare.scheduling.application.WaitlistService;
import com.queuecare.scheduling.application.WaitlistService.MineView;
import com.queuecare.scheduling.application.WaitlistService.StaffView;
import com.queuecare.scheduling.persistence.WaitlistEntry;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Lista de espera (ADR 0022): o tutor pede e vê as próprias entradas; a equipe vê quem espera um dia, chama e tira. */
@RestController
class WaitlistController {

	private final WaitlistService waitlist;

	WaitlistController(WaitlistService waitlist) {
		this.waitlist = waitlist;
	}

	record JoinRequest(@NotNull UUID providerId, @NotNull UUID serviceId, @NotNull UUID petId, @NotNull LocalDate date) {
	}

	record EntryResponse(UUID id, UUID providerId, UUID serviceId, UUID petId, LocalDate date, Instant createdAt) {
		static EntryResponse from(WaitlistEntry w) {
			return new EntryResponse(w.getId(), w.getProviderId(), w.getServiceId(), w.getPetId(), w.getWantedDate(), w.getCreatedAt());
		}
	}

	record MineResponse(UUID id, UUID providerId, String businessName, String serviceName, String petName, LocalDate date, Instant createdAt) {
		static MineResponse from(MineView v) {
			return new MineResponse(v.id(), v.providerId(), v.businessName(), v.serviceName(), v.petName(), v.wantedDate(), v.createdAt());
		}
	}

	/** {@code durationMinutes}: a duração prevista PARA ESSE PET (ADR 0020); {@code calledAt}: quando a equipe chamou. */
	record StaffResponse(UUID id, UUID customerId, String customerName, String customerPhone, String petName, String petSpecies, String serviceName, int durationMinutes,
			Instant createdAt, Instant calledAt) {
		static StaffResponse from(StaffView v) {
			return new StaffResponse(v.id(), v.customerId(), v.customerName(), v.customerPhone(), v.petName(), v.petSpecies(), v.serviceName(), v.durationMinutes(), v.createdAt(),
					v.calledAt());
		}
	}

	@PostMapping("/api/waitlist")
	@ResponseStatus(HttpStatus.CREATED)
	@PreAuthorize("hasRole('CUSTOMER')")
	EntryResponse join(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody JoinRequest r) {
		return EntryResponse.from(waitlist.join(userId(jwt), r.providerId(), r.serviceId(), r.petId(), r.date()));
	}

	@GetMapping("/api/waitlist/mine")
	@PreAuthorize("hasRole('CUSTOMER')")
	List<MineResponse> mine(@AuthenticationPrincipal Jwt jwt) {
		return waitlist.mine(userId(jwt)).stream().map(MineResponse::from).toList();
	}

	@DeleteMapping("/api/waitlist/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@PreAuthorize("hasAnyRole('CUSTOMER','PROVIDER','ATTENDANT')")
	void remove(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
		waitlist.remove(userId(jwt), id);
	}

	@GetMapping("/api/agenda/waitlist")
	@PreAuthorize("hasAnyRole('PROVIDER','ATTENDANT')")
	List<StaffResponse> day(@AuthenticationPrincipal Jwt jwt, @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
		return waitlist.day(userId(jwt), date).stream().map(StaffResponse::from).toList();
	}

	@PostMapping("/api/waitlist/{id}/called")
	@PreAuthorize("hasAnyRole('PROVIDER','ATTENDANT')")
	EntryResponse called(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
		return EntryResponse.from(waitlist.markCalled(userId(jwt), id));
	}

	private static UUID userId(Jwt jwt) {
		return UUID.fromString(jwt.getSubject());
	}
}
