package com.queuecare.demo;

import com.queuecare.auth.AuthService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Entrada da demonstração pública (ADR 0041). SÓ existe com {@code queuecare.demo.enabled=true}: desligada, esta rota responde 404 como qualquer outra inexistente
 * e não aparece no contrato OpenAPI. Fica sob {@code /api/auth/**} (pública, como o login) e tem o limite por minuto do login.
 */
@RestController
@ConditionalOnProperty(name = "queuecare.demo.enabled", havingValue = "true")
class DemoController {

	/** Mesma forma da resposta do login (o site reaproveita o mesmo tratamento do token). */
	record TokenResponse(String accessToken, String tokenType, long expiresIn, boolean mustChangePassword) {
	}

	record DemoLoginRequest(@NotNull DemoService.Profile profile) {
	}

	private final DemoService demo;

	DemoController(DemoService demo) {
		this.demo = demo;
	}

	@PostMapping("/api/auth/demo")
	TokenResponse enter(@Valid @RequestBody DemoLoginRequest request) {
		AuthService.IssuedToken token = demo.enter(request.profile());
		return new TokenResponse(token.accessToken(), "Bearer", token.expiresInSeconds(), token.mustChangePassword());
	}
}
