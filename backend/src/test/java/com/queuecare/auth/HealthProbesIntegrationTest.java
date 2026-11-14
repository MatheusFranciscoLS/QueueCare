package com.queuecare.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

/**
 * Vivacidade e prontidão SEPARADAS (ADR 0013): com o banco "caído" (indicador do banco substituído por um que responde DOWN), o health
 * agregado e a prontidão dizem 503, mas a VIVACIDADE continua 200. É a vivacidade que o Render consulta, então o Neon dormindo não
 * faz a plataforma reiniciar a API em laço. As rotas de saúde são públicas e só dizem UP/DOWN (sem detalhes).
 */
@Import(HealthProbesIntegrationTest.DatabaseDown.class)
class HealthProbesIntegrationTest extends AuthIntegrationTestBase {

	@TestConfiguration
	static class DatabaseDown {
		/** O nome "dbHealthIndicator" substitui o indicador real do DataSource. */
		@Bean
		HealthIndicator dbHealthIndicator() {
			return () -> Health.down().build();
		}
	}

	@Test
	void comOBancoCaidoAVivacidadeContinuaUpEAProntidaoEOAgregadoCaem() throws Exception {
		mvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
		mvc.perform(get("/actuator/health/readiness")).andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.status").value("DOWN"));
		mvc.perform(get("/actuator/health")).andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.status").value("DOWN"))
				.andExpect(jsonPath("$.components").doesNotExist());
	}
}
