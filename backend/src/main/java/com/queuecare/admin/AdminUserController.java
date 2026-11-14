package com.queuecare.admin;

import com.queuecare.auth.PasswordService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import com.queuecare.scheduling.persistence.UserRole;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Administração de credenciais: SOMENTE o ADMIN (rota {@code /api/admin/**} e anotação). A senha nunca volta em resposta. */
@RestController
@RequestMapping("/api/admin/users")
@PreAuthorize("hasRole('ADMIN')")
class AdminUserController {

	static final int MAX_PAGE_SIZE = 50;
	static final int MAX_QUERY_LENGTH = 100;

	private final PasswordService passwordService;
	private final AdminUserService userService;

	AdminUserController(PasswordService passwordService, AdminUserService userService) {
		this.passwordService = passwordService;
		this.userService = userService;
	}

	record ResetPasswordRequest(@NotBlank @Size(max = 256) String newInitialPassword) {
	}

	/** Contas (sem senha nem hash): {@code role} e {@code q} são filtros opcionais; {@code size} de 1 a 50. */
	record UserResponse(UUID id, String name, String email, String role, boolean active, boolean mustChangePassword, java.time.Instant createdAt) {
		static UserResponse from(AdminUserService.AdminUserView v) {
			return new UserResponse(v.id(), v.name(), v.email(), v.role().name(), v.active(), v.mustChangePassword(), v.createdAt());
		}
	}

	@GetMapping
	java.util.List<UserResponse> list(@RequestParam(required = false) String role, @RequestParam(required = false) String q, @RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "20") int size) {
		if (page < 0 || size < 1 || size > MAX_PAGE_SIZE || (q != null && q.length() > MAX_QUERY_LENGTH)) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "parâmetros inválidos");
		}
		UserRole parsed = null;
		if (role != null && !role.isBlank()) {
			try {
				parsed = UserRole.valueOf(role);
			} catch (IllegalArgumentException e) {
				throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "perfil inválido");
			}
		}
		return userService.list(parsed, q, page, size).stream().map(UserResponse::from).toList();
	}

	@PostMapping("/{userId}/reset-password")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void resetPassword(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID userId, @Valid @RequestBody ResetPasswordRequest request) {
		passwordService.resetPassword(UUID.fromString(jwt.getSubject()), userId, request.newInitialPassword());
	}

	@PostMapping("/{userId}/deactivate")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void deactivate(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID userId) {
		userService.deactivate(UUID.fromString(jwt.getSubject()), userId);
	}

	@PostMapping("/{userId}/activate")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void activate(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID userId) {
		userService.activate(UUID.fromString(jwt.getSubject()), userId);
	}
}
