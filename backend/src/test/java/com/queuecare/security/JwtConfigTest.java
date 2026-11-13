package com.queuecare.security;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import org.junit.jupiter.api.Test;

/** O segredo do JWT é obrigatório, em Base64 e com ao menos 32 bytes; sem isso a aplicação não pode subir (ADR 0003). */
class JwtConfigTest {

	private static JwtProperties withSecret(String secret) {
		return new JwtProperties(secret, "queuecare-api", "queuecare-api", Duration.ofMinutes(15), Duration.ofSeconds(30));
	}

	private static String base64Of(int bytes) {
		return Base64.getEncoder().encodeToString("x".repeat(bytes).getBytes(StandardCharsets.UTF_8));
	}

	@Test
	void segredoAusenteEhRecusado() {
		assertThrows(IllegalStateException.class, () -> new JwtConfig().jwtSecretKey(withSecret(null)));
	}

	@Test
	void segredoQueNaoEhBase64EhRecusado() {
		assertThrows(IllegalStateException.class, () -> new JwtConfig().jwtSecretKey(withSecret("isto não é base64 !!!")));
	}

	@Test
	void segredoComMenosDe32BytesEhRecusado() {
		assertThrows(IllegalStateException.class, () -> new JwtConfig().jwtSecretKey(withSecret(base64Of(31))));
	}

	@Test
	void segredoComPeloMenos32BytesEhAceito() {
		assertDoesNotThrow(() -> new JwtConfig().jwtSecretKey(withSecret(base64Of(32))));
		assertDoesNotThrow(() -> new JwtConfig().jwtSecretKey(withSecret(base64Of(64))));
	}
}
