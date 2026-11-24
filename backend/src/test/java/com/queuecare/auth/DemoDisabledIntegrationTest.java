package com.queuecare.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/** Com a demonstração desligada (o padrão em qualquer ambiente que não ligue DEMO_ENABLED) a rota não existe e nenhuma conta fictícia é criada (ADR 0041). */
class DemoDisabledIntegrationTest extends AuthIntegrationTestBase {

	@Test
	void theRouteDoesNotExistAndNothingIsCreated() throws Exception {
		mvc.perform(post("/api/auth/demo").contentType(MediaType.APPLICATION_JSON).content(json("profile", "OWNER"))).andExpect(status().isNotFound());
		assertEquals(0, count("SELECT count(*) FROM users WHERE email LIKE '%@demo.invalid'"));
	}
}
