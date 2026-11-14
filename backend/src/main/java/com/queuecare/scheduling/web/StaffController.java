package com.queuecare.scheduling.web;

import com.queuecare.scheduling.application.StaffService;
import com.queuecare.scheduling.application.StaffService.StaffMember;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
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

/**
 * Equipe do PRÓPRIO negócio: só PROVIDER. O prestador é sempre o do token; nenhum id de prestador entra na requisição, e
 * campos extras no corpo (como "role" ou "providerId") são ignorados.
 */
@RestController
@RequestMapping("/api/provider/staff")
@PreAuthorize("hasRole('PROVIDER')")
class StaffController {

	private final StaffService staffService;

	StaffController(StaffService staffService) {
		this.staffService = staffService;
	}

	record LinkRequest(@NotBlank @Size(max = 254) String email) {
	}

	/** O perfil NÃO é um campo: a conta criada é sempre ATTENDANT (campos desconhecidos, como role, são ignorados). */
	record CreateAccountRequest(
			@NotBlank @Size(max = 120) String name,
			@NotBlank @jakarta.validation.constraints.Email @Size(max = 254) String email,
			@NotBlank @Size(max = 256) String initialPassword) {
		/** Remove espaços externos ANTES da validação, como no cadastro público. */
		CreateAccountRequest {
			name = name == null ? null : name.trim();
			email = email == null ? null : email.trim();
		}
	}

	record StaffResponse(UUID userId, String name, String email) {
		static StaffResponse from(StaffMember m) {
			return new StaffResponse(m.userId(), m.name(), m.email());
		}
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	StaffResponse link(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody LinkRequest request) {
		return StaffResponse.from(staffService.link(userId(jwt), request.email()));
	}

	@PostMapping("/accounts")
	@ResponseStatus(HttpStatus.CREATED)
	StaffResponse createAccount(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CreateAccountRequest request) {
		return StaffResponse.from(staffService.createAttendantAccount(userId(jwt), request.name(), request.email(), request.initialPassword()));
	}

	@GetMapping
	List<StaffResponse> list(@AuthenticationPrincipal Jwt jwt) {
		return staffService.list(userId(jwt)).stream().map(StaffResponse::from).toList();
	}

	@PostMapping("/{userId}/deactivate")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void deactivate(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID userId) {
		staffService.deactivateAttendant(userId(jwt), userId);
	}

	@DeleteMapping("/{userId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void unlink(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID userId) {
		staffService.unlink(userId(jwt), userId);
	}

	private static UUID userId(Jwt jwt) {
		return UUID.fromString(jwt.getSubject());
	}
}
