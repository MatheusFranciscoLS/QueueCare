package com.queuecare.scheduling.web;

import com.queuecare.scheduling.application.PublicStudioService;
import com.queuecare.scheduling.application.PublicStudioService.PublicService;
import com.queuecare.scheduling.application.PublicStudioService.PublicStudio;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Página pública do estúdio: aberta a qualquer visitante (rota liberada na segurança, com limite por minuto por endereço). */
@RestController
@RequestMapping("/api/public")
class PublicStudioController {

	private final PublicStudioService studios;

	PublicStudioController(PublicStudioService studios) {
		this.studios = studios;
	}

	record ServiceResponse(String name, int durationMinutes) {
		static ServiceResponse from(PublicService s) {
			return new ServiceResponse(s.name(), s.durationMinutes());
		}
	}

	record StudioResponse(String slug, String businessName, String timeZone, List<ServiceResponse> services) {
		static StudioResponse from(PublicStudio s) {
			return new StudioResponse(s.slug(), s.businessName(), s.timeZone(), s.services().stream().map(ServiceResponse::from).toList());
		}
	}

	@ApiResponse(responseCode = "200", description = "Estúdio publicado.", content = @Content(schema = @Schema(implementation = StudioResponse.class)))
	@ApiResponse(responseCode = "404", description = "Endereço inexistente, inválido, não publicado ou de dono inativo: sempre a mesma resposta (reason STUDIO_NOT_FOUND).")
	@GetMapping("/studios/{slug}")
	StudioResponse studio(@PathVariable String slug) {
		return StudioResponse.from(studios.find(slug));
	}
}
