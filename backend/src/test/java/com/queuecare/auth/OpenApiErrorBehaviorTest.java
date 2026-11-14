package com.queuecare.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;

/**
 * O que o contrato OpenAPI promete para 401, 403 e 429 é exatamente o que a API ENTREGA (status, Content-Type, cabeçalhos e corpo):
 * 401 e 403 por perfil vêm da cadeia de segurança com corpo vazio; a troca de senha obrigatória e o 429 têm corpo problem+json.
 */
@TestPropertySource(properties = { "queuecare.rate-limit.login-per-minute=1", "queuecare.rate-limit.slots-per-minute=1", "queuecare.rate-limit.password-change-attempts=1" })
class OpenApiErrorBehaviorTest extends AuthIntegrationTestBase {

	@Test
	void semTokenOuComTokenInvalidoRecebe401ComCorpoVazioECabecalhoBearer() throws Exception {
		MvcResult none = mvc.perform(get("/api/admin/users")).andExpect(status().isUnauthorized()).andReturn();
		assertEquals("", none.getResponse().getContentAsString());
		assertNotNull(none.getResponse().getHeader(HttpHeaders.WWW_AUTHENTICATE));
		assertTrue(none.getResponse().getHeader(HttpHeaders.WWW_AUTHENTICATE).startsWith("Bearer"));
		MvcResult invalid = mvc.perform(get("/api/admin/users").header(HttpHeaders.AUTHORIZATION, "Bearer invalido")).andExpect(status().isUnauthorized()).andReturn();
		assertEquals("", invalid.getResponse().getContentAsString());
	}

	@Test
	void perfilSemPermissaoRecebe403ComCorpoVazio() throws Exception {
		String customer = "Bearer " + validToken(seedUser("cliente@exemplo.test", "CUSTOMER", true));
		MvcResult result = mvc.perform(get("/api/admin/users").header(HttpHeaders.AUTHORIZATION, customer)).andExpect(status().isForbidden()).andReturn();
		assertEquals("", result.getResponse().getContentAsString());
	}

	@Test
	void trocaDeSenhaObrigatoriaRecebe403ProblemJsonComReason() throws Exception {
		seedUser("admin@exemplo.test", "ADMIN", true, true);
		String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(json("email", "admin@exemplo.test", "password", PASSWORD)))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		String token = "Bearer " + JsonPath.read(body, "$.accessToken");
		MvcResult result = mvc.perform(get("/api/admin/users").header(HttpHeaders.AUTHORIZATION, token)).andExpect(status().isForbidden())
				.andExpect(jsonPath("$.reason").value("PASSWORD_CHANGE_REQUIRED")).andExpect(jsonPath("$.status").value(403)).andExpect(jsonPath("$.title").value("Forbidden"))
				.andExpect(jsonPath("$.type").value("about:blank")).andReturn();
		assertTrue(result.getResponse().getContentType().startsWith("application/problem+json"), result.getResponse().getContentType());
	}

	@Test
	void excessoDePedidosRecebe429ProblemJsonComRetryAfter() throws Exception {
		String login = json("email", "ninguem@exemplo.test", "password", PASSWORD);
		mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(login));
		MvcResult result = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(login)).andExpect(status().isTooManyRequests())
				.andExpect(jsonPath("$.status").value(429)).andExpect(jsonPath("$.title").value("Too Many Requests")).andReturn();
		assertTrue(result.getResponse().getContentType().startsWith("application/problem+json"), result.getResponse().getContentType());
		assertNotNull(result.getResponse().getHeader("Retry-After"));
	}

	@Test
	void catalogoLimitadoRecebe429ProblemJsonMasRotaNaoLimitadaNuncaRecebe() throws Exception {
		String customer = "Bearer " + validToken(seedUser("cliente@exemplo.test", "CUSTOMER", true));
		mvc.perform(get("/api/catalog/providers").header(HttpHeaders.AUTHORIZATION, customer)).andExpect(status().isOk());
		MvcResult limited = mvc.perform(get("/api/catalog/providers").header(HttpHeaders.AUTHORIZATION, customer)).andExpect(status().isTooManyRequests()).andReturn();
		assertTrue(limited.getResponse().getContentType().startsWith("application/problem+json"));
		// uma rota que o contrato NÃO marca com 429 realmente nunca o devolve, por mais que se repita
		for (int i = 0; i < 6; i++) {
			mvc.perform(get("/api/appointments/mine").header(HttpHeaders.AUTHORIZATION, customer)).andExpect(status().isOk());
		}
	}

	@Test
	void trocaDeSenhaAcimaDoLimiteDeTentativasRecebe429ProblemJsonComRetryAfter() throws Exception {
		String token = "Bearer " + validToken(seedUser("cliente@exemplo.test", "CUSTOMER", true));
		String wrong = json("currentPassword", PASSWORD + "-errada", "newPassword", PASSWORD + "-nova");
		MvcResult first = mvc.perform(post("/api/auth/change-password").header(HttpHeaders.AUTHORIZATION, token).contentType(MediaType.APPLICATION_JSON).content(wrong)).andReturn();
		assertTrue(first.getResponse().getStatus() != 429, "a primeira tentativa ainda é conferida");
		MvcResult second = mvc.perform(post("/api/auth/change-password").header(HttpHeaders.AUTHORIZATION, token).contentType(MediaType.APPLICATION_JSON).content(wrong))
				.andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.status").value(429)).andReturn();
		assertTrue(second.getResponse().getContentType().startsWith("application/problem+json"), second.getResponse().getContentType());
		assertNotNull(second.getResponse().getHeader("Retry-After"));
	}
}
