package com.queuecare.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** Limite de requisições por minuto em login, cadastro e horários livres (valores baixos só neste teste). */
@TestPropertySource(properties = {
		"queuecare.rate-limit.login-per-minute=3",
		"queuecare.rate-limit.register-per-minute=2",
		"queuecare.rate-limit.slots-per-minute=3",
		"queuecare.rate-limit.login-failures-per-email-and-ip=100000"
})
class RateLimitEndpointsIntegrationTest extends AuthIntegrationTestBase {

	private static RequestPostProcessor from(String address) {
		return request -> {
			request.setRemoteAddr(address);
			return request;
		};
	}

	private ResultActions login(String email, String password, String address) throws Exception {
		return mvc.perform(post("/api/auth/login").with(from(address)).contentType(MediaType.APPLICATION_JSON).content(json("email", email, "password", password)));
	}

	private ResultActions register(String email, String address) throws Exception {
		return mvc.perform(post("/api/auth/register").with(from(address)).contentType(MediaType.APPLICATION_JSON)
				.content(json("name", "Fulano", "email", email, "password", PASSWORD)));
	}

	@Test
	void loginEhLimitadoPorEnderecoComRetryAfterECorpoGenerico() throws Exception {
		seedUser("maria@exemplo.test", "CUSTOMER", true);

		login("maria@exemplo.test", PASSWORD, "10.0.0.1").andExpect(status().isOk());
		login("maria@exemplo.test", "errada-1234", "10.0.0.1").andExpect(status().isUnauthorized());
		login("ninguem@exemplo.test", PASSWORD, "10.0.0.1").andExpect(status().isUnauthorized());

		String retryAfter = login("maria@exemplo.test", PASSWORD, "10.0.0.1").andExpect(status().isTooManyRequests())
				.andExpect(header().exists(HttpHeaders.RETRY_AFTER))
				.andExpect(jsonPath("$.status").value(429))
				.andReturn().getResponse().getHeader(HttpHeaders.RETRY_AFTER);
		assertTrue(Long.parseLong(retryAfter) >= 1 && Long.parseLong(retryAfter) <= 60, "Retry-After: " + retryAfter);
		// Outro endereço não é afetado.
		login("maria@exemplo.test", PASSWORD, "10.0.0.2").andExpect(status().isOk());
	}

	@Test
	void cadastroEhLimitadoPorEndereco() throws Exception {
		register("a@exemplo.test", "10.0.0.1").andExpect(status().isCreated());
		register("b@exemplo.test", "10.0.0.1").andExpect(status().isCreated());

		register("c@exemplo.test", "10.0.0.1").andExpect(status().isTooManyRequests()).andExpect(header().exists(HttpHeaders.RETRY_AFTER));
		assertEquals(2L, count("SELECT count(*) FROM users"), "o cadastro bloqueado não criou usuário");
		register("c@exemplo.test", "10.0.0.2").andExpect(status().isCreated());
	}

	@Test
	void horariosLivresSaoLimitadosPorUsuarioEMantemOLimiteSeparadoPorUsuario() throws Exception {
		UUID user1 = seedUser("u1@exemplo.test", "CUSTOMER", true);
		UUID user2 = seedUser("u2@exemplo.test", "CUSTOMER", true);
		String token1 = "Bearer " + validToken(user1);
		String token2 = "Bearer " + validToken(user2);
		String path = "/api/providers/" + UUID.randomUUID() + "/slots";

		for (int i = 0; i < 3; i++) {
			// prestador inexistente: 404, mas a chamada CONTA no limite do usuário
			mvc.perform(get(path).param("serviceId", UUID.randomUUID().toString()).param("date", "2030-01-01").header(HttpHeaders.AUTHORIZATION, token1))
					.andExpect(status().isNotFound());
		}
		mvc.perform(get(path).param("serviceId", UUID.randomUUID().toString()).param("date", "2030-01-01").header(HttpHeaders.AUTHORIZATION, token1))
				.andExpect(status().isTooManyRequests());
		mvc.perform(get(path).param("serviceId", UUID.randomUUID().toString()).param("date", "2030-01-01").header(HttpHeaders.AUTHORIZATION, token2))
				.andExpect(status().isNotFound());
	}

	@Test
	void rotasSemLimiteNaoSaoAfetadas() throws Exception {
		UUID user = seedUser("u1@exemplo.test", "CUSTOMER", true);
		String token = "Bearer " + validToken(user);

		for (int i = 0; i < 10; i++) {
			mvc.perform(get("/api/me").header(HttpHeaders.AUTHORIZATION, token)).andExpect(status().isOk());
		}
	}

	@Test
	void respostaDeLimiteNaoVazaDadosDaRequisicao() throws Exception {
		login("a@exemplo.test", "segredo-que-nao-pode-voltar", "10.0.0.9");
		login("a@exemplo.test", "segredo-que-nao-pode-voltar", "10.0.0.9");
		login("a@exemplo.test", "segredo-que-nao-pode-voltar", "10.0.0.9");

		String body = login("a@exemplo.test", "segredo-que-nao-pode-voltar", "10.0.0.9").andExpect(status().isTooManyRequests()).andReturn().getResponse().getContentAsString();

		assertTrue(!body.contains("segredo-que-nao-pode-voltar") && !body.contains("a@exemplo.test"), body);
		assertEquals("Too Many Requests", JsonPath.read(body, "$.title"));
	}
}
