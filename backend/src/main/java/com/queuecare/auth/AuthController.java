package com.queuecare.auth;

import com.queuecare.auth.AuthDtos.LoginRequest;
import com.queuecare.auth.AuthDtos.RegisterRequest;
import com.queuecare.auth.AuthDtos.TokenResponse;
import com.queuecare.auth.AuthDtos.UserResponse;
import com.queuecare.scheduling.persistence.UserAccount;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Cadastro público (sempre CUSTOMER) e login. Públicos: não exigem token. */
@RestController
@RequestMapping("/api/auth")
class AuthController {

	private final AuthService authService;

	AuthController(AuthService authService) {
		this.authService = authService;
	}

	@PostMapping("/register")
	@ResponseStatus(HttpStatus.CREATED)
	UserResponse register(@Valid @RequestBody RegisterRequest request) {
		UserAccount user = authService.registerCustomer(request.name(), request.email(), request.password());
		return toResponse(user);
	}

	@PostMapping("/login")
	TokenResponse login(@Valid @RequestBody LoginRequest request, HttpServletRequest http) {
		AuthService.IssuedToken token = authService.login(request.email(), request.password(), http.getRemoteAddr());
		return new TokenResponse(token.accessToken(), "Bearer", token.expiresInSeconds());
	}

	static UserResponse toResponse(UserAccount user) {
		return new UserResponse(user.getId(), user.getName(), user.getEmail(), user.getRole().name());
	}
}
