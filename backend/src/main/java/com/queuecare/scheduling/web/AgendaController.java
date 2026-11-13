package com.queuecare.scheduling.web;

import com.queuecare.scheduling.application.AgendaService;
import com.queuecare.scheduling.application.AgendaService.AgendaItem;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Agenda do dia do prestador em que o usuário OPERA (dono ou atendente vinculado). Não existe parâmetro de prestador:
 * ele é sempre resolvido no servidor a partir do usuário autenticado; um "providerId" na URL é simplesmente ignorado.
 */
@RestController
class AgendaController {

	private final AgendaService agendaService;

	AgendaController(AgendaService agendaService) {
		this.agendaService = agendaService;
	}

	record AgendaResponse(UUID id, Instant startsAt, Instant endsAt, String status, UUID serviceId, String serviceName, UUID customerId,
			String customerName) {
		static AgendaResponse from(AgendaItem i) {
			return new AgendaResponse(i.id(), i.startsAt(), i.endsAt(), i.status().name(), i.serviceId(), i.serviceName(), i.customerId(), i.customerName());
		}
	}

	@GetMapping("/api/agenda")
	@PreAuthorize("hasAnyRole('PROVIDER','ATTENDANT')")
	List<AgendaResponse> day(@AuthenticationPrincipal Jwt jwt, @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
		return agendaService.day(UUID.fromString(jwt.getSubject()), date).stream().map(AgendaResponse::from).toList();
	}
}
