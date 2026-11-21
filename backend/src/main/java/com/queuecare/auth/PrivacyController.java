package com.queuecare.auth;

import com.queuecare.auth.PrivacyService.Export;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Os direitos do tutor sobre os próprios dados (ADR 0027). A pessoa vem SEMPRE do token. Só o tutor. */
@RestController
class PrivacyController {

	private final PrivacyService privacy;

	PrivacyController(PrivacyService privacy) {
		this.privacy = privacy;
	}

	record DeleteAccountRequest(@NotBlank @Size(max = AuthDtos.MAX_PASSWORD_CHARACTERS) String password) {
	}

	/** Baixa tudo o que o app guarda do tutor, como arquivo JSON (sem cache: são dados pessoais). */
	@GetMapping("/api/me/export")
	@PreAuthorize("hasRole('CUSTOMER')")
	ResponseEntity<Export> export(@AuthenticationPrincipal Jwt jwt) {
		return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store").header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"meus-dados.json\"")
				.body(privacy.export(UUID.fromString(jwt.getSubject())));
	}

	/** Exclui a conta (anonimiza no lugar) depois de conferir a senha atual. */
	@PostMapping("/api/me/delete-account")
	@PreAuthorize("hasRole('CUSTOMER')")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void delete(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody DeleteAccountRequest request) {
		privacy.deleteAccount(UUID.fromString(jwt.getSubject()), request.password());
	}
}
