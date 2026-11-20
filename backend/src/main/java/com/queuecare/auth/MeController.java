package com.queuecare.auth;

import com.queuecare.auth.AuthDtos.UpdatePhoneRequest;
import com.queuecare.auth.AuthDtos.UserResponse;
import com.queuecare.scheduling.persistence.UserAccount;
import jakarta.validation.Valid;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
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

	/** Troca o PRÓPRIO telefone (a pessoa vem do token). Vazio remove. Formato inválido: 400 com errors.phone. */
	@PutMapping("/api/me/phone")
	@Transactional
	UserResponse updatePhone(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody UpdatePhoneRequest request) {
		UserAccount user = users.findById(UUID.fromString(jwt.getSubject())).orElseThrow();
		user.setPhone(PhoneNumber.normalize(request.phone()));
		return AuthController.toResponse(users.saveAndFlush(user));
	}
}
