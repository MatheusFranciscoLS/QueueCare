package com.queuecare.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

/** Sem OPENAPI_ENABLED o endpoint do contrato NÃO existe (nem para um ADMIN autenticado): a API não se descreve sozinha em produção. */
class OpenApiDisabledByDefaultTest extends AuthIntegrationTestBase {

	@Test
	void apiDocsDesligadoPorPadraoParaTodos() throws Exception {
		String admin = "Bearer " + validToken(seedUser("admin@exemplo.test", "ADMIN", true));
		mvc.perform(get("/v3/api-docs").header(HttpHeaders.AUTHORIZATION, admin)).andExpect(status().isNotFound());
		mvc.perform(get("/v3/api-docs")).andExpect(status().isUnauthorized());
		mvc.perform(get("/swagger-ui.html").header(HttpHeaders.AUTHORIZATION, admin)).andExpect(status().isNotFound());
	}
}
