package com.queuecare.scheduling.web;

import com.queuecare.scheduling.application.AgendaService;
import com.queuecare.scheduling.application.AgendaService.AgendaItem;
import com.queuecare.scheduling.application.AgendaService.QueueItem;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
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

	/**
	 * Pet do agendamento (cópia gravada no agendamento). Na agenda do dia e na fila vêm raça e observações; na visão da semana vêm nulas
	 * (só nome, espécie e porte). Ausente (nulo) em agendamento sem pet.
	 */
	record AgendaPetResponse(String name, String species, String breed, String size, String notes, String coat, java.math.BigDecimal weightKg,
			String temperament, String healthNotes, Boolean vaccinated) {
		static AgendaPetResponse from(AgendaService.PetInfo p) {
			return p == null ? null : new AgendaPetResponse(p.name(), p.species().name(), p.breed(), p.size() == null ? null : p.size().name(), p.notes(),
					p.coat() == null ? null : p.coat().name(), p.weightKg(), p.temperament() == null ? null : p.temperament().name(), p.healthNotes(), p.vaccinated());
		}
	}

	/**
	 * {@code customerPhone}: só dígitos com DDI; presente na agenda do dia e na fila, nulo na semana e quando o tutor não informou.
	 * {@code priceCents}: valor congelado na reserva (ADR 0019), também só no dia e na fila; nulo na semana e quando era "a combinar".
	 */
	record AgendaResponse(UUID id, Instant startsAt, Instant endsAt, String status, UUID serviceId, String serviceName, UUID customerId,
			String customerName, String customerPhone, Integer priceCents, AgendaPetResponse pet) {
		static AgendaResponse from(AgendaItem i) {
			return new AgendaResponse(i.id(), i.startsAt(), i.endsAt(), i.status().name(), i.serviceId(), i.serviceName(), i.customerId(), i.customerName(),
					i.customerPhone(), i.priceCents(), AgendaPetResponse.from(i.pet()));
		}
	}

	/** {@code status}: CHECKED_IN (esperando), IN_PROGRESS (em atendimento) ou READY (pronto para retirada); ADR 0019. */
	record QueueResponse(int position, String status, UUID id, Instant startsAt, Instant endsAt, UUID serviceId, String serviceName, UUID customerId, String customerName,
			String customerPhone, Integer priceCents, Instant checkedInAt, AgendaPetResponse pet) {
		static QueueResponse from(QueueItem i) {
			return new QueueResponse(i.position(), i.status().name(), i.id(), i.startsAt(), i.endsAt(), i.serviceId(), i.serviceName(), i.customerId(), i.customerName(), i.customerPhone(), i.priceCents(), i.checkedInAt(),
					AgendaPetResponse.from(i.pet()));
		}
	}

	/** O id do prestador serve para a equipe consultar horários livres (remarcar); é o mesmo id público do catálogo. */
	record WorkplaceResponse(UUID providerId, String businessName, String timeZone) {
	}

	/** Negócio e fuso do lugar onde a pessoa trabalha (dono ou atendente vinculado); os horários da agenda valem nesse fuso. */
	@GetMapping("/api/agenda/workplace")
	@PreAuthorize("hasAnyRole('PROVIDER','ATTENDANT')")
	WorkplaceResponse workplace(@AuthenticationPrincipal Jwt jwt) {
		AgendaService.Workplace w = agendaService.workplace(UUID.fromString(jwt.getSubject()));
		return new WorkplaceResponse(w.providerId(), w.businessName(), w.timeZone());
	}

	@ApiResponse(responseCode = "200", description = "Agendamentos não cancelados, por horário.", content = @Content(array = @ArraySchema(schema = @Schema(implementation = AgendaResponse.class))))
	@ApiResponse(responseCode = "400", description = "Data ausente, inválida ou fora de 1970-01-01 a 2100-12-31 (application/problem+json).", content = @Content(mediaType = "application/problem+json", schema = @Schema(ref = "#/components/schemas/Problem")))
	@ApiResponse(responseCode = "404", description = "Prestador sem negócio cadastrado (reason PROVIDER_NOT_CONFIGURED).", content = @Content(mediaType = "application/problem+json", schema = @Schema(ref = "#/components/schemas/Problem")))
	@GetMapping("/api/agenda")
	@PreAuthorize("hasAnyRole('PROVIDER','ATTENDANT')")
	List<AgendaResponse> day(@AuthenticationPrincipal Jwt jwt, @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
		return agendaService.day(UUID.fromString(jwt.getSubject()), date).stream().map(AgendaResponse::from).toList();
	}

	/** Os 7 dias a partir de {@code start} (data local do prestador), para o calendário semanal. Mesmas regras e a mesma resposta da agenda do dia. */
	@ApiResponse(responseCode = "200", description = "Agendamentos não cancelados, por horário.", content = @Content(array = @ArraySchema(schema = @Schema(implementation = AgendaResponse.class))))
	@ApiResponse(responseCode = "400", description = "Data ausente, inválida ou fora de 1970-01-01 a 2100-12-31 (application/problem+json).", content = @Content(mediaType = "application/problem+json", schema = @Schema(ref = "#/components/schemas/Problem")))
	@ApiResponse(responseCode = "404", description = "Prestador sem negócio cadastrado (reason PROVIDER_NOT_CONFIGURED).", content = @Content(mediaType = "application/problem+json", schema = @Schema(ref = "#/components/schemas/Problem")))
	@GetMapping("/api/agenda/week")
	@PreAuthorize("hasAnyRole('PROVIDER','ATTENDANT')")
	List<AgendaResponse> week(@AuthenticationPrincipal Jwt jwt, @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate start) {
		return agendaService.week(UUID.fromString(jwt.getSubject()), start).stream().map(AgendaResponse::from).toList();
	}

	/** Fila de hoje do prestador em que a pessoa opera, por ordem de chegada. */
	@GetMapping("/api/agenda/queue")
	@PreAuthorize("hasAnyRole('PROVIDER','ATTENDANT')")
	List<QueueResponse> queue(@AuthenticationPrincipal Jwt jwt) {
		return agendaService.queue(UUID.fromString(jwt.getSubject())).stream().map(QueueResponse::from).toList();
	}
}
