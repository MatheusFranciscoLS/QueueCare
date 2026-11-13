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

	/** Folga generosa sobre o limite real de 72 bytes do BCrypt (que continua validado no serviço). */
	static final int MAX_PASSWORD_CHARACTERS = 256;

	private AuthDtos() {
	}

	record RegisterRequest(
			@NotBlank @Size(max = 120) String name,
			@NotBlank @Email @Size(max = 254) String email,
			@NotBlank @Size(max = MAX_PASSWORD_CHARACTERS) String password) {
		/** Remove espaços externos ANTES da validação, para "  ana@exemplo.test " ser aceito e normalizado. */
		RegisterRequest {
			name = name == null ? null : name.trim();
			email = email == null ? null : email.trim();
		}
	}

	/** Tamanhos máximos validados ANTES de qualquer uso (inclusive da chave do bloqueio de login): entrada não cresce sem limite. */
	record LoginRequest(
			@NotBlank @Size(max = 254) String email,
			@NotBlank @Size(max = MAX_PASSWORD_CHARACTERS) String password) {
	}

	record UserResponse(UUID id, String name, String email, String role) {
	}

	record TokenResponse(String accessToken, String tokenType, long expiresIn) {
	}
}
