package com.queuecare.scheduling.web;

import com.queuecare.scheduling.application.ProviderAdminService;
import com.queuecare.scheduling.application.ProviderAdminService.WindowInput;
import com.queuecare.scheduling.persistence.AvailabilityWindowEntity;
import com.queuecare.scheduling.persistence.Provider;
import com.queuecare.scheduling.persistence.ServiceOffering;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * O prestador configura o PRÓPRIO negócio. Só o perfil PROVIDER entra; o negócio é sempre o do usuário do token
 * (nenhum id de prestador vem do cliente), então um prestador nunca enxerga nem altera o de outro.
 */
@RestController
@RequestMapping("/api/provider")
@PreAuthorize("hasRole('PROVIDER')")
class ProviderController {

	private final ProviderAdminService admin;

	ProviderController(ProviderAdminService admin) {
		this.admin = admin;
	}

	record BusinessRequest(@NotNull String businessName, @NotNull String timeZone, @NotNull Integer minimumNoticeMinutes,
			@NotNull Integer cancellationDeadlineMinutes) {
	}

	record BusinessResponse(UUID id, String businessName, String timeZone, int minimumNoticeMinutes, int cancellationDeadlineMinutes) {
		static BusinessResponse from(Provider p) {
			return new BusinessResponse(p.getId(), p.getBusinessName(), p.getTimeZone(), p.getMinimumNoticeMinutes(), p.getCancellationDeadlineMinutes());
		}
	}

	/** Preços em centavos (ADR 0019): {@code priceCents} é o base; os de porte médio e grande, quando ausentes, usam o base; todos ausentes = a combinar. */
	record ServiceRequest(@NotNull String name, @NotNull Integer durationMinutes, Integer durationMediumMinutes, Integer durationLargeMinutes, Integer coatExtraMinutes,
			Integer priceCents, Integer priceMediumCents, Integer priceLargeCents) {
	}

	/** No PUT, active é obrigatório: omitir o campo não pode reativar um serviço desativado sem querer. */
	record ServiceUpdateRequest(@NotNull String name, @NotNull Integer durationMinutes, Integer durationMediumMinutes, Integer durationLargeMinutes,
			Integer coatExtraMinutes, @NotNull Boolean active, Integer priceCents, Integer priceMediumCents, Integer priceLargeCents) {
	}

	record ServiceResponse(UUID id, String name, int durationMinutes, Integer durationMediumMinutes, Integer durationLargeMinutes, int coatExtraMinutes, boolean active,
			Integer priceCents, Integer priceMediumCents, Integer priceLargeCents) {
		static ServiceResponse from(ServiceOffering s) {
			return new ServiceResponse(s.getId(), s.getName(), s.getDurationMinutes(), s.getDurationMediumMinutes(), s.getDurationLargeMinutes(), s.getCoatExtraMinutes(),
					s.isActive(), s.getPriceCents(), s.getPriceMediumCents(), s.getPriceLargeCents());
		}
	}

	record WindowRequest(@NotNull DayOfWeek dayOfWeek, @NotNull LocalTime start, @NotNull LocalTime end) {
	}

	record WindowResponse(DayOfWeek dayOfWeek, LocalTime start, LocalTime end) {
		static WindowResponse from(AvailabilityWindowEntity w) {
			return new WindowResponse(w.dayOfWeek(), w.getStartTime(), w.getEndTime());
		}
	}

	// --- negócio ---

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	BusinessResponse create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody BusinessRequest r) {
		return BusinessResponse.from(admin.createBusiness(userId(jwt), r.businessName(), r.timeZone(), r.minimumNoticeMinutes(), r.cancellationDeadlineMinutes()));
	}

	@GetMapping
	BusinessResponse get(@AuthenticationPrincipal Jwt jwt) {
		return BusinessResponse.from(admin.getBusiness(userId(jwt)));
	}

	@PutMapping
	BusinessResponse update(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody BusinessRequest r) {
		return BusinessResponse.from(admin.updateBusiness(userId(jwt), r.businessName(), r.timeZone(), r.minimumNoticeMinutes(), r.cancellationDeadlineMinutes()));
	}

	// --- página pública (ADR 0015) ---

	record PublicPageRequest(String slug, @NotNull Boolean published) {
	}

	record PublicPageResponse(String slug, boolean published) {
		static PublicPageResponse from(Provider p) {
			return new PublicPageResponse(p.getSlug(), p.isPublished());
		}
	}

	@GetMapping("/public-page")
	PublicPageResponse publicPage(@AuthenticationPrincipal Jwt jwt) {
		return PublicPageResponse.from(admin.getBusiness(userId(jwt)));
	}

	@ApiResponse(responseCode = "200", description = "Endereço e publicação gravados.", content = @Content(schema = @Schema(implementation = PublicPageResponse.class)))
	@ApiResponse(responseCode = "400", description = "Endereço inválido, reservado, ou publicar sem endereço (errors.slug).", content = @Content(mediaType = "application/problem+json", schema = @Schema(ref = "#/components/schemas/Problem")))
	@ApiResponse(responseCode = "404", description = "Prestador sem negócio cadastrado.", content = @Content(mediaType = "application/problem+json", schema = @Schema(ref = "#/components/schemas/Problem")))
	@ApiResponse(responseCode = "409", description = "Endereço já em uso por outro estúdio (reason SLUG_TAKEN).", content = @Content(mediaType = "application/problem+json", schema = @Schema(ref = "#/components/schemas/Problem")))
	@PutMapping("/public-page")
	PublicPageResponse updatePublicPage(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody PublicPageRequest r) {
		return PublicPageResponse.from(admin.updatePublicPage(userId(jwt), r.slug(), r.published()));
	}

	// --- serviços ---

	@GetMapping("/services")
	List<ServiceResponse> services(@AuthenticationPrincipal Jwt jwt) {
		return admin.listServices(userId(jwt)).stream().map(ServiceResponse::from).toList();
	}

	@PostMapping("/services")
	@ResponseStatus(HttpStatus.CREATED)
	ServiceResponse createService(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody ServiceRequest r) {
		return ServiceResponse.from(admin.createService(userId(jwt), r.name(), r.durationMinutes(),
				new ProviderAdminService.Durations(r.durationMediumMinutes(), r.durationLargeMinutes(), r.coatExtraMinutes()), r.priceCents(), r.priceMediumCents(),
				r.priceLargeCents()));
	}

	@PutMapping("/services/{id}")
	ServiceResponse updateService(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody ServiceUpdateRequest r) {
		return ServiceResponse.from(admin.updateService(userId(jwt), id, r.name(), r.durationMinutes(),
				new ProviderAdminService.Durations(r.durationMediumMinutes(), r.durationLargeMinutes(), r.coatExtraMinutes()), r.active(), r.priceCents(),
				r.priceMediumCents(), r.priceLargeCents()));
	}

	// --- janelas de atendimento ---

	@GetMapping("/availability")
	List<WindowResponse> availability(@AuthenticationPrincipal Jwt jwt) {
		return admin.getAvailability(userId(jwt)).stream().map(WindowResponse::from).toList();
	}

	/**
	 * Substitui TODAS as janelas pela lista enviada (o cliente precisa enviar o conjunto completo; lista vazia = não atende).
	 * Dois PUTs simultâneos: vale o último a confirmar (last writer wins); o resultado é sempre um conjunto válido.
	 */
	@PutMapping("/availability")
	List<WindowResponse> replaceAvailability(@AuthenticationPrincipal Jwt jwt, @RequestBody List<@Valid WindowRequest> windows) {
		List<WindowInput> inputs = windows.stream().map(w -> new WindowInput(w.dayOfWeek(), w.start(), w.end())).toList();
		return admin.replaceAvailability(userId(jwt), inputs).stream().map(WindowResponse::from).toList();
	}

	private static UUID userId(Jwt jwt) {
		return UUID.fromString(jwt.getSubject());
	}
}
