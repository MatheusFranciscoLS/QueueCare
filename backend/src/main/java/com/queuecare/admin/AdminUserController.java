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
import org.springframework.web.bind.annotation.PathVariable;
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

	private final PasswordService passwordService;
	private final AdminUserService userService;

	AdminUserController(PasswordService passwordService, AdminUserService userService) {
		this.passwordService = passwordService;
		this.userService = userService;
	}

	record ResetPasswordRequest(@NotBlank @Size(max = 256) String newInitialPassword) {
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
