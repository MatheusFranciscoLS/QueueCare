package com.queuecare.security;

import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Limitador compartilhado pelo filtro de requisições e pelo bloqueio de falhas de login. */
@Configuration
@EnableConfigurationProperties(RateLimitProperties.class)
class RateLimitConfig {

	@Bean
	RateLimiter rateLimiter(Clock clock) {
		return new RateLimiter(clock);
	}
}
