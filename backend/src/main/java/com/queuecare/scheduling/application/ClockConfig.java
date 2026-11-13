package com.queuecare.scheduling.application;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Relógio da aplicação (UTC). Os testes substituem por um relógio fixo para resultados determinísticos. */
@Configuration
class ClockConfig {

	@Bean
	Clock clock() {
		return Clock.systemUTC();
	}
}
