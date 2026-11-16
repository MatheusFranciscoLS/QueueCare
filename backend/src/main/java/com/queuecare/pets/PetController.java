package com.queuecare.pets;

import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Pets do tutor (ADR 0016). Só o perfil CUSTOMER, e SEMPRE os próprios pets: o dono vem do token, nunca da requisição.
 * Prestador, atendente e administrador não têm acesso a este cadastro (o prestador vê o pet pelo agendamento, em outro PR).
 */
@RestController
@RequestMapping("/api/customer/pets")
@PreAuthorize("hasRole('CUSTOMER')")
class PetController {

	private static final String PROBLEM = "#/components/schemas/Problem";

	private final PetService service;

	PetController(PetService service) {
		this.service = service;
	}

	/** Dados do pet (criar e substituir). Textos como o tutor digitou: a API normaliza e valida (veja PetText). */
	record PetRequest(String name, String species, String breed, String size, String notes) {
		PetText.Input toInput() {
			return new PetText.Input(name, species, breed, size, notes);
		}
	}

	record PetResponse(UUID id, String name, String species, String breed, String size, String notes, boolean active) {
		static PetResponse from(Pet p) {
			return new PetResponse(p.getId(), p.getName(), p.getSpecies().name(), p.getBreed(), p.getSize() == null ? null : p.getSize().name(),
					p.getNotes(), p.isActive());
		}
	}

	@ApiResponse(responseCode = "200", description = "Pets do cliente, por nome. Só os ativos, a menos que includeArchived=true.")
	@GetMapping
	List<PetResponse> list(@AuthenticationPrincipal Jwt jwt, @RequestParam(defaultValue = "false") boolean includeArchived) {
		return service.list(userId(jwt), includeArchived).stream().map(PetResponse::from).toList();
	}

	@ApiResponse(responseCode = "400", description = "Dados inválidos (errors.name, errors.species, errors.breed, errors.size, errors.notes).", content = @Content(mediaType = "application/problem+json", schema = @Schema(ref = PROBLEM)))
	@ApiResponse(responseCode = "409", description = "Limite de pets ativos (20) ou cadastrados (100) atingido (reason PET_LIMIT_REACHED).", content = @Content(mediaType = "application/problem+json", schema = @Schema(ref = PROBLEM)))
	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	PetResponse create(@AuthenticationPrincipal Jwt jwt, @RequestBody PetRequest request) {
		return PetResponse.from(service.create(userId(jwt), request.toInput()));
	}

	@ApiResponse(responseCode = "404", description = "Pet inexistente ou de outro cliente (reason PET_NOT_FOUND).", content = @Content(mediaType = "application/problem+json", schema = @Schema(ref = PROBLEM)))
	@GetMapping("/{id}")
	PetResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
		return PetResponse.from(service.get(userId(jwt), id));
	}

	@ApiResponse(responseCode = "400", description = "Dados inválidos (errors.name, errors.species, errors.breed, errors.size, errors.notes).", content = @Content(mediaType = "application/problem+json", schema = @Schema(ref = PROBLEM)))
	@ApiResponse(responseCode = "404", description = "Pet inexistente ou de outro cliente (reason PET_NOT_FOUND).", content = @Content(mediaType = "application/problem+json", schema = @Schema(ref = PROBLEM)))
	@PutMapping("/{id}")
	PetResponse update(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @RequestBody PetRequest request) {
		return PetResponse.from(service.update(userId(jwt), id, request.toInput()));
	}

	@ApiResponse(responseCode = "404", description = "Pet inexistente ou de outro cliente (reason PET_NOT_FOUND).", content = @Content(mediaType = "application/problem+json", schema = @Schema(ref = PROBLEM)))
	@PostMapping("/{id}/archive")
	PetResponse archive(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
		return PetResponse.from(service.archive(userId(jwt), id));
	}

	@ApiResponse(responseCode = "404", description = "Pet inexistente ou de outro cliente (reason PET_NOT_FOUND).", content = @Content(mediaType = "application/problem+json", schema = @Schema(ref = PROBLEM)))
	@ApiResponse(responseCode = "409", description = "Já existem 20 pets ativos (reason PET_LIMIT_REACHED).", content = @Content(mediaType = "application/problem+json", schema = @Schema(ref = PROBLEM)))
	@PostMapping("/{id}/reactivate")
	PetResponse reactivate(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
		return PetResponse.from(service.reactivate(userId(jwt), id));
	}

	private static UUID userId(Jwt jwt) {
		return UUID.fromString(jwt.getSubject());
	}
}
