package com.queuecare.scheduling.application;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** Relógio fixo dos testes: segunda-feira 16/11/2026, 12:00 UTC (09:00 em São Paulo). */
@TestConfiguration
class FixedClockConfig {

	static final Instant NOW = Instant.parse("2026-11-16T12:00:00Z");

	@Bean
	@Primary
	Clock testClock() {
		return Clock.fixed(NOW, ZoneOffset.UTC);
	}
}
