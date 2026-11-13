package com.queuecare.security;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuração do JWT (ADR 0003). O segredo vem SEMPRE de variável de ambiente ({@code JWT_SECRET}), em Base64, com pelo
 * menos 256 bits (32 bytes) de entropia; não há valor padrão e a aplicação não sobe sem ele.
 *
 * @param secret segredo HS256 em Base64
 * @param issuer emissor esperado e gravado nos tokens
 * @param audience destinatário esperado (esta API); impede aceitar token emitido para outro serviço
 * @param accessTokenTtl validade do token de acesso
 * @param clockSkew tolerância pequena e explícita para diferença de relógio
 */
@ConfigurationProperties(prefix = "queuecare.jwt")
public record JwtProperties(
		String secret,
		@DefaultValue("queuecare-api") String issuer,
		@DefaultValue("queuecare-api") String audience,
		@DefaultValue("15m") Duration accessTokenTtl,
		@DefaultValue("30s") Duration clockSkew) {
}
