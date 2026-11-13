package com.queuecare.security;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
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

	// --- o validador usa o relógio INJETADO (iat, exp), não o do sistema ---

	private static final Instant FIXED = Instant.parse("2020-01-01T12:00:00Z");

	private static String token(SecretKey key, Instant issuedAt, Instant expiresAt) {
		JwtClaimsSet claims = JwtClaimsSet.builder().issuer("queuecare-api").audience(List.of("queuecare-api")).subject(UUID.randomUUID().toString())
				.issuedAt(issuedAt).expiresAt(expiresAt).build();
		return new NimbusJwtEncoder(new ImmutableSecret<>(key)).encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
	}

	@Test
	void validadorDeJwtUsaORelogioInjetado() {
		JwtConfig config = new JwtConfig();
		JwtProperties props = withSecret(base64Of(32));
		SecretKey key = config.jwtSecretKey(props);
		JwtDecoder decoder = config.jwtDecoder(key, props, Clock.fixed(FIXED, ZoneOffset.UTC));

		// Em 2020 (relógio injetado) este token está dentro da validade, embora no relógio real já tenha expirado há anos.
		assertDoesNotThrow(() -> decoder.decode(token(key, FIXED, FIXED.plusSeconds(900))));
		// exp no passado (relativo ao relógio injetado), iat no futuro e iat depois de exp são recusados.
		assertThrows(JwtException.class, () -> decoder.decode(token(key, FIXED.minusSeconds(7200), FIXED.minusSeconds(3600))));
		assertThrows(JwtException.class, () -> decoder.decode(token(key, FIXED.plusSeconds(600), FIXED.plusSeconds(1500))));
		assertDoesNotThrow(() -> decoder.decode(token(key, FIXED.plusSeconds(10), FIXED.plusSeconds(910)))); // dentro da tolerância de 30 s
	}
}
