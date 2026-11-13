package com.queuecare.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;

/** Tamanhos de entrada limitados: e-mail e senha de login/cadastro (validação) e corpo de qualquer requisição (filtro). */
@TestPropertySource(properties = "queuecare.http.max-body-bytes=2048")
class RequestLimitsIntegrationTest extends AuthIntegrationTestBase {

	private int login(String email, String password) throws Exception {
		return mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(json("email", email, "password", password)))
				.andReturn().getResponse().getStatus();
	}

	@Test
	void emailDeLoginGrandeDemaisEhRecusadoAntesDeVirarChaveDoLimitador() throws Exception {
		seedUser("maria@exemplo.test", "CUSTOMER", true);

		assertEquals(400, login("a".repeat(255) + "@exemplo.test", PASSWORD));
		assertEquals(400, login("a".repeat(1_000) + "@exemplo.test", PASSWORD));
		assertEquals(200, login("maria@exemplo.test", PASSWORD), "tamanho normal segue funcionando");
	}

	@Test
	void senhaGrandeDemaisEhRecusadaNoLoginENoCadastro() throws Exception {
		seedUser("maria@exemplo.test", "CUSTOMER", true);

		assertEquals(400, login("maria@exemplo.test", "x".repeat(257)));
		assertEquals(401, login("maria@exemplo.test", "x".repeat(100)), "acima de 72 bytes mas dentro do limite: credenciais inválidas");
		assertEquals(400, mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
				.content(json("name", "Fulano", "email", "novo@exemplo.test", "password", "x".repeat(257)))).andReturn().getResponse().getStatus());
		assertEquals(0L, count("SELECT count(*) FROM users WHERE email = 'novo@exemplo.test'"));
	}

	@Test
	void corpoMaiorQueOLimiteVira413EmQualquerRota() throws Exception {
		String huge = json("email", "maria@exemplo.test", "password", "x".repeat(5_000));

		mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(huge)).andExpect(status().isContentTooLarge());
		mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(huge)).andExpect(status().isContentTooLarge());
		mvc.perform(put("/api/provider/availability").contentType(MediaType.APPLICATION_JSON).content(huge)).andExpect(status().isContentTooLarge());
	}
}
