package com.queuecare.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.TestPropertySource;

/** O catálogo tem limite por usuário (mesmo limite dos horários livres), para não virar varredura em massa. */
@TestPropertySource(properties = "queuecare.rate-limit.slots-per-minute=3")
class CatalogRateLimitIntegrationTest extends AuthIntegrationTestBase {

	private int status(String path, String bearer) throws Exception {
		return mvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, bearer)).andReturn().getResponse().getStatus();
	}

	@Test
	void quartaConsultaDoMesmoUsuarioRecebe429EOutroUsuarioNao() throws Exception {
		String one = "Bearer " + validToken(seedUser("um@exemplo.test", "CUSTOMER", true));
		String two = "Bearer " + validToken(seedUser("dois@exemplo.test", "CUSTOMER", true));

		for (int i = 0; i < 3; i++) {
			assertEquals(200, status("/api/catalog/providers", one));
		}
		assertEquals(429, status("/api/catalog/providers", one));
		assertEquals(200, status("/api/catalog/providers", two));
	}
}
