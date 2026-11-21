package com.queuecare.scheduling.web;

import com.queuecare.scheduling.application.SlotService;
import com.queuecare.scheduling.application.SlotService.Slot;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Horários livres de um serviço num dia (data local do prestador). Exige login; qualquer perfil autenticado pode consultar. */
@RestController
class SlotController {

	private final SlotService slotService;

	SlotController(SlotService slotService) {
		this.slotService = slotService;
	}

	record SlotResponse(LocalDateTime localStart, Instant startsAt, Instant endsAt) {
		static SlotResponse from(Slot s) {
			return new SlotResponse(s.localStart(), s.startsAt(), s.endsAt());
		}
	}

	@GetMapping("/api/providers/{providerId}/slots")
	@org.springframework.security.access.prepost.PreAuthorize("hasAnyRole('CUSTOMER','PROVIDER','ATTENDANT')")
	List<SlotResponse> slots(@org.springframework.security.core.annotation.AuthenticationPrincipal org.springframework.security.oauth2.jwt.Jwt jwt, @PathVariable UUID providerId, @RequestParam UUID serviceId,
			@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date, @RequestParam(required = false) UUID excludeAppointmentId, @RequestParam(required = false) UUID petId) {
		// excludeAppointmentId (remarcar): ignorado se o agendamento não for do chamador, sem erro e sem pista (ADR 0017, item 8).
		// petId (agendar): a duração depende do pet; ignorado se não for um pet ativo do chamador (ADR 0020).
		return slotService.freeSlots(providerId, serviceId, date, excludeAppointmentId, UUID.fromString(jwt.getSubject()), petId).stream().map(SlotResponse::from).toList();
	}
}
