package com.queuecare.admin;

import com.queuecare.admin.AdminProviderService.CreatedProvider;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Administração de contas: SOMENTE o perfil ADMIN (regra em duas camadas: rota {@code /api/admin/**} e esta anotação).
 * O perfil da conta criada é fixo no código (PROVIDER); a senha inicial nunca volta em resposta.
 */
@RestController
@RequestMapping("/api/admin")
@PreAuthorize("hasRole('ADMIN')")
class AdminProviderController {

	private final AdminProviderService service;

	AdminProviderController(AdminProviderService service) {
		this.service = service;
	}

	record CreateProviderRequest(
			@NotBlank @Size(max = 120) String name,
			@NotBlank @Email @Size(max = 254) String email,
			@NotBlank @Size(max = 256) String initialPassword,
			@NotBlank @Size(max = 160) String businessName,
			@NotBlank @Size(max = 64) String timeZone,
			@NotNull Integer minimumNoticeMinutes,
			@NotNull Integer cancellationDeadlineMinutes) {
		/** Remove espaços externos ANTES da validação, como no cadastro público. */
		CreateProviderRequest {
			name = name == null ? null : name.trim();
			email = email == null ? null : email.trim();
			businessName = businessName == null ? null : businessName.trim();
		}
	}

	record CreatedProviderResponse(UUID userId, String name, String email, UUID providerId, String businessName, boolean mustChangePassword) {
		static CreatedProviderResponse from(CreatedProvider p) {
			return new CreatedProviderResponse(p.userId(), p.name(), p.email(), p.providerId(), p.businessName(), true);
		}
	}

	@PostMapping("/providers")
	@ResponseStatus(HttpStatus.CREATED)
	CreatedProviderResponse createProvider(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CreateProviderRequest r) {
		return CreatedProviderResponse.from(service.createProvider(UUID.fromString(jwt.getSubject()), r.name(), r.email(), r.initialPassword(),
				r.businessName(), r.timeZone(), r.minimumNoticeMinutes(), r.cancellationDeadlineMinutes()));
	}
}
