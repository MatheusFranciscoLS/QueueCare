package com.queuecare.security;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.util.Base64;
import java.util.List;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import java.util.UUID;

/**
 * Chave, emissor e validação dos JWT (ADR 0003): somente HS256, emissor e audiência conferidos, {@code exp} e {@code iat}
 * obrigatórios, {@code sub} precisa ser um UUID e tolerância de relógio pequena.
 */
@Configuration
@EnableConfigurationProperties(JwtProperties.class)
class JwtConfig {

	static final int MIN_SECRET_BYTES = 32;

	@Bean
	SecretKey jwtSecretKey(JwtProperties properties) {
		byte[] bytes;
		try {
			bytes = Base64.getDecoder().decode(properties.secret());
		} catch (IllegalArgumentException | NullPointerException e) {
			throw new IllegalStateException("queuecare.jwt.secret (variável JWT_SECRET) é obrigatório e deve estar em Base64");
		}
		if (bytes.length < MIN_SECRET_BYTES) {
			throw new IllegalStateException("queuecare.jwt.secret deve ter ao menos " + MIN_SECRET_BYTES + " bytes (256 bits) depois de decodificar o Base64");
		}
		return new SecretKeySpec(bytes, "HmacSHA256");
	}

	@Bean
	JwtEncoder jwtEncoder(SecretKey jwtSecretKey) {
		return new NimbusJwtEncoder(new ImmutableSecret<>(jwtSecretKey));
	}

	@Bean
	JwtDecoder jwtDecoder(SecretKey jwtSecretKey, JwtProperties properties) {
		NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(jwtSecretKey).macAlgorithm(MacAlgorithm.HS256).build();
		decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
				new JwtTimestampValidator(properties.clockSkew()),
				new JwtIssuerValidator(properties.issuer()),
				requiredClaims(properties.audience())));
		return decoder;
	}

	private static OAuth2TokenValidator<Jwt> requiredClaims(String audience) {
		return jwt -> {
			if (jwt.getClaim(JwtClaimNames.EXP) == null) {
				return failure("o token precisa ter exp");
			}
			if (jwt.getClaim(JwtClaimNames.IAT) == null) {
				return failure("o token precisa ter iat");
			}
			List<String> audiences = jwt.getAudience();
			if (audiences == null || !audiences.contains(audience)) {
				return failure("audiência inválida");
			}
			try {
				UUID.fromString(String.valueOf(jwt.getClaimAsString(JwtClaimNames.SUB)));
			} catch (IllegalArgumentException e) {
				return failure("sub deve ser um UUID");
			}
			return OAuth2TokenValidatorResult.success();
		};
	}

	private static OAuth2TokenValidatorResult failure(String description) {
		return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", description, null));
	}
}
