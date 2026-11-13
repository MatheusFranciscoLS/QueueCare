package com.queuecare.auth;

import com.queuecare.auth.AuthDtos.UserResponse;
import com.queuecare.scheduling.persistence.UserAccountRepository;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Quem sou eu: devolve o usuário do token. A identidade vem SEMPRE do principal autenticado, nunca do corpo da requisição. */
@RestController
class MeController {

	private final UserAccountRepository users;

	MeController(UserAccountRepository users) {
		this.users = users;
	}

	@GetMapping("/api/me")
	UserResponse me(@AuthenticationPrincipal Jwt jwt) {
		return AuthController.toResponse(users.findById(UUID.fromString(jwt.getSubject())).orElseThrow());
	}
}
