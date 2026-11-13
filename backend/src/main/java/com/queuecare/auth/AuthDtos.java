package com.queuecare.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * DTOs de entrada e saída da autenticação. Entidades JPA nunca são expostas: assim o hash da senha e campos internos
 * não saem por acidente. O cadastro NÃO tem campo de perfil (role): campos desconhecidos no JSON são ignorados.
 */
final class AuthDtos {

	private AuthDtos() {
	}

	record RegisterRequest(
			@NotBlank @Size(max = 120) String name,
			@NotBlank @Email @Size(max = 254) String email,
			@NotBlank String password) {
		/** Remove espaços externos ANTES da validação, para "  ana@exemplo.test " ser aceito e normalizado. */
		RegisterRequest {
			name = name == null ? null : name.trim();
			email = email == null ? null : email.trim();
		}
	}

	record LoginRequest(@NotBlank String email, @NotBlank String password) {
	}

	record UserResponse(UUID id, String name, String email, String role) {
	}

	record TokenResponse(String accessToken, String tokenType, long expiresIn) {
	}
}
