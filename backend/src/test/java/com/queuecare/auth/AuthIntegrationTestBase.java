package com.queuecare.auth;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.queuecare.database.PostgresTestSupport;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Base dos testes de segurança: aplicação Spring completa (MockMvc, filtros do Spring Security reais) com PostgreSQL real
 * embutido. O segredo JWT abaixo é EXCLUSIVO de teste.
 */
/** Limites de requisição BEM altos por padrão (os testes fazem muitas chamadas do mesmo endereço); testes específicos os reduzem. */
@TestPropertySource(properties = {
		"queuecare.rate-limit.login-per-minute=100000",
		"queuecare.rate-limit.register-per-minute=100000",
		"queuecare.rate-limit.slots-per-minute=100000",
		"queuecare.rate-limit.login-failures-per-email-and-ip=100000",
		"queuecare.rate-limit.password-change-attempts=100000"
})
@SpringBootTest
@AutoConfigureMockMvc
abstract class AuthIntegrationTestBase {

	/** 32 bytes em Base64: segredo exclusivo de teste. */
	static final String TEST_SECRET = "dGVzdGUtZXhjbHVzaXZvLWRlLXRlc3RlLTMyLWJ5dGVzLSEh";
	static final String OTHER_SECRET = Base64.getEncoder().encodeToString("outro-segredo-diferente-de-32-bytes-ok".getBytes(StandardCharsets.UTF_8));
	static final String ISSUER = "queuecare-api";
	static final String AUDIENCE = "queuecare-api";
	static final String PASSWORD = "senha-forte-123";

	@DynamicPropertySource
	static void properties(DynamicPropertyRegistry registry) throws SQLException {
		String url = PostgresTestSupport.newEmptyDatabaseJdbcUrl();
		registry.add("spring.datasource.url", () -> url);
		registry.add("spring.datasource.username", () -> "postgres");
		registry.add("spring.datasource.password", () -> "teste");
		registry.add("spring.flyway.enabled", () -> "true");
		registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
		registry.add("queuecare.jwt.secret", () -> TEST_SECRET);
		registry.add("queuecare.security.bcrypt-strength", () -> "4");
		registry.add("queuecare.cors.allowed-origins", () -> "http://localhost:5173");
	}

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	PasswordEncoder passwordEncoder;

	@Autowired
	@org.springframework.beans.factory.annotation.Qualifier(com.queuecare.security.RateLimiter.REQUEST_LIMITER)
	com.queuecare.security.RateLimiter requestLimiter;

	@Autowired
	@org.springframework.beans.factory.annotation.Qualifier(com.queuecare.security.RateLimiter.LOGIN_LOCKOUT_LIMITER)
	com.queuecare.security.RateLimiter loginLockoutLimiter;

	@BeforeEach
	void cleanUsers() {
		// Os limitadores são em memória e compartilhados pelo contexto: cada teste começa do zero.
		requestLimiter.clearAll();
		loginLockoutLimiter.clearAll();
		jdbc.execute("TRUNCATE appointment_events, appointments, availability_windows, services, providers, users CASCADE");
	}

	/** Cria um usuário diretamente no banco, com senha {@link #PASSWORD} em BCrypt. */
	UUID seedUser(String email, String role, boolean active) {
		return jdbc.queryForObject(
				"INSERT INTO users (name, email, password_hash, role, active) VALUES ('Fulano de Teste', ?, ?, ?, ?) RETURNING id",
				UUID.class, email, passwordEncoder.encode(PASSWORD), role, active);
	}

	/** Como {@link #seedUser(String, String, boolean)}, escolhendo se a conta ainda precisa trocar a senha inicial. */
	UUID seedUser(String email, String role, boolean active, boolean mustChangePassword) {
		return jdbc.queryForObject(
				"INSERT INTO users (name, email, password_hash, role, active, must_change_password) VALUES ('Fulano de Teste', ?, ?, ?, ?, ?) RETURNING id",
				UUID.class, email, passwordEncoder.encode(PASSWORD), role, active, mustChangePassword);
	}

	long count(String sql, Object... args) {
		return jdbc.queryForObject(sql, Long.class, args);
	}

	/** JSON simples a partir de pares chave, valor (com escape de aspas e barras). */
	static String json(String... keyValues) {
		StringBuilder sb = new StringBuilder("{");
		for (int i = 0; i < keyValues.length; i += 2) {
			if (i > 0) {
				sb.append(',');
			}
			sb.append('"').append(escape(keyValues[i])).append("\":\"").append(escape(keyValues[i + 1])).append('"');
		}
		return sb.append('}').toString();
	}

	private static String escape(String s) {
		return s.replace("\\", "\\\\").replace("\"", "\\\"");
	}

	/** Gera um JWT assinado com o segredo informado; um parâmetro nulo omite o respectivo claim. */
	static String token(String base64Secret, String issuer, String audience, String subject, Instant issuedAt, Instant expiresAt, String role) {
		SecretKeySpec key = new SecretKeySpec(Base64.getDecoder().decode(base64Secret), "HmacSHA256");
		NimbusJwtEncoder encoder = new NimbusJwtEncoder(new ImmutableSecret<>(key));
		JwtClaimsSet.Builder claims = JwtClaimsSet.builder();
		if (issuer != null) {
			claims.issuer(issuer);
		}
		if (audience != null) {
			claims.audience(List.of(audience));
		}
		if (subject != null) {
			claims.subject(subject);
		}
		if (issuedAt != null) {
			claims.issuedAt(issuedAt);
		}
		if (expiresAt != null) {
			claims.expiresAt(expiresAt);
		}
		if (role != null) {
			claims.claim("role", role);
		}
		return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims.build())).getTokenValue();
	}

	/** JWT assinado com claims escolhidos à mão (o encoder do Spring preenche iat sozinho, então para testar a ausência dele montamos direto). */
	static String rawSignedToken(String base64Secret, String claimsJson) throws Exception {
		com.nimbusds.jwt.SignedJWT jwt = new com.nimbusds.jwt.SignedJWT(new com.nimbusds.jose.JWSHeader(com.nimbusds.jose.JWSAlgorithm.HS256),
				com.nimbusds.jwt.JWTClaimsSet.parse(claimsJson));
		jwt.sign(new com.nimbusds.jose.crypto.MACSigner(Base64.getDecoder().decode(base64Secret)));
		return jwt.serialize();
	}

	/** Token válido (15 minutos) para o usuário. */
	static String validToken(UUID userId) {
		Instant now = Instant.now();
		return token(TEST_SECRET, ISSUER, AUDIENCE, userId.toString(), now, now.plusSeconds(900), "CUSTOMER");
	}

	/** JWT sem assinatura (alg none), montado à mão. */
	static String unsignedToken(UUID userId) {
		Instant now = Instant.now();
		String header = b64("{\"alg\":\"none\",\"typ\":\"JWT\"}");
		String payload = b64("{\"iss\":\"" + ISSUER + "\",\"aud\":\"" + AUDIENCE + "\",\"sub\":\"" + userId + "\",\"iat\":" + now.getEpochSecond()
				+ ",\"exp\":" + now.plusSeconds(900).getEpochSecond() + "}");
		return header + "." + payload + ".";
	}

	private static String b64(String s) {
		return Base64.getUrlEncoder().withoutPadding().encodeToString(s.getBytes(StandardCharsets.UTF_8));
	}
}
