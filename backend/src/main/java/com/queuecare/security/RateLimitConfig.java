package com.queuecare.security;

import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Dois limitadores independentes: um para o filtro de requisições por minuto e outro SÓ para o bloqueio por falhas de login.
 * Separados, o tráfego comum nunca disputa memória com os bloqueios de força bruta (nem consegue expulsá-los).
 */
@Configuration
@EnableConfigurationProperties(RateLimitProperties.class)
class RateLimitConfig {

	@Bean(RateLimiter.REQUEST_LIMITER)
	RateLimiter requestRateLimiter(Clock clock) {
		return new RateLimiter(clock);
	}

	@Bean(RateLimiter.LOGIN_LOCKOUT_LIMITER)
	RateLimiter loginLockoutLimiter(Clock clock) {
		return new RateLimiter(clock);
	}
}
