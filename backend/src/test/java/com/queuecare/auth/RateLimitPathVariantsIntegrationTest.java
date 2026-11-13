package com.queuecare.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Fronteira do filtro de limite: variações do caminho (barra final, maiúsculas, barras repetidas, parâmetro de caminho,
 * codificação, ponto-ponto) e cabeçalhos de proxy NÃO podem servir o endpoint sem passar pelo limite. Cada variação ou é
 * recusada antes do controller (400/404/405 do firewall e do roteamento) ou é contada (429): o que nunca pode acontecer é a
 * requisição ser ATENDIDA com o limite já esgotado.
 */
@TestPropertySource(properties = {
		"queuecare.rate-limit.login-per-minute=3",
		"queuecare.rate-limit.register-per-minute=2",
		"queuecare.rate-limit.slots-per-minute=3",
		"queuecare.rate-limit.login-failures-per-email-and-ip=100000"
})
class RateLimitPathVariantsIntegrationTest extends AuthIntegrationTestBase {

	private static final List<String> LOGIN_VARIANTS = List.of(
			"/api/auth/login/", "/API/AUTH/LOGIN", "/Api/Auth/Login", "//api/auth/login", "/api//auth/login",
			"/api/auth/login;x=1", "/api/auth;x=1/login", "/api/auth/./login", "/api/auth/x/../login",
			"/api/auth/%6Cogin", "/api/auth/login%2F", "/api/auth/login%00", "/api/auth/login.json", "/api/auth/login?a=b", "/api/auth/login#frag");

	private static RequestPostProcessor from(String address) {
		return request -> {
			request.setRemoteAddr(address);
			return request;
		};
	}

	private MockHttpServletResponse loginAt(String path, String email, String address) throws Exception {
		return mvc.perform(post(URI.create(path)).with(from(address)).contentType(MediaType.APPLICATION_JSON)
				.content(json("email", email, "password", PASSWORD))).andReturn().getResponse();
	}

	@Test
	void variacoesDoCaminhoDeLoginNaoServemOLoginComOLimiteEsgotado() throws Exception {
		seedUser("maria@exemplo.test", "CUSTOMER", true);
		for (int i = 0; i < 3; i++) {
			assertEquals(200, loginAt("/api/auth/login", "maria@exemplo.test", "10.0.0.1").getStatus());
		}
		assertEquals(429, loginAt("/api/auth/login", "maria@exemplo.test", "10.0.0.1").getStatus(), "o caminho exato está limitado");

		List<String> served = new ArrayList<>();
		for (String variant : LOGIN_VARIANTS) {
			MockHttpServletResponse response = loginAt(variant, "maria@exemplo.test", "10.0.0.1");
			if (response.getStatus() == 200) {
				served.add(variant + " -> 200");
			}
		}

		assertTrue(served.isEmpty(), "variações servidas pelo login mesmo com o limite esgotado: " + served);
	}

	@Test
	void variacoesDoCaminhoDeCadastroNaoCriamUsuarioComOLimiteEsgotado() throws Exception {
		for (int i = 0; i < 2; i++) {
			assertEquals(201, mvc.perform(post("/api/auth/register").with(from("10.0.0.1")).contentType(MediaType.APPLICATION_JSON)
					.content(json("name", "Fulano", "email", "ok" + i + "@exemplo.test", "password", PASSWORD))).andReturn().getResponse().getStatus());
		}
		assertEquals(429, mvc.perform(post("/api/auth/register").with(from("10.0.0.1")).contentType(MediaType.APPLICATION_JSON)
				.content(json("name", "Fulano", "email", "x@exemplo.test", "password", PASSWORD))).andReturn().getResponse().getStatus());

		int n = 0;
		for (String variant : List.of("/api/auth/register/", "/API/AUTH/REGISTER", "//api/auth/register", "/api/auth/register;x=1",
				"/api/auth/./register", "/api/auth/%72egister", "/api/auth/register.json")) {
			mvc.perform(post(URI.create(variant)).with(from("10.0.0.1")).contentType(MediaType.APPLICATION_JSON)
					.content(json("name", "Fulano", "email", "v" + n++ + "@exemplo.test", "password", PASSWORD)));
		}

		assertEquals(2L, count("SELECT count(*) FROM users"), "nenhuma variação conseguiu cadastrar com o limite esgotado");
	}

	@Test
	void variacoesDoCaminhoDeHorariosLivresNaoFogemDoLimitePorUsuario() throws Exception {
		UUID user = seedUser("u1@exemplo.test", "CUSTOMER", true);
		String bearer = "Bearer " + validToken(user);
		String providerId = UUID.randomUUID().toString();
		String query = "?serviceId=" + UUID.randomUUID() + "&date=2030-01-01";
		for (int i = 0; i < 3; i++) {
			assertNotEquals(429, mvc.perform(get(URI.create("/api/providers/" + providerId + "/slots" + query)).header(HttpHeaders.AUTHORIZATION, bearer))
					.andReturn().getResponse().getStatus());
		}
		assertEquals(429, mvc.perform(get(URI.create("/api/providers/" + providerId + "/slots" + query)).header(HttpHeaders.AUTHORIZATION, bearer))
				.andReturn().getResponse().getStatus());

		List<String> reachedHandler = new ArrayList<>();
		for (String variant : List.of("/api/providers/" + providerId + "/slots/", "/API/PROVIDERS/" + providerId + "/SLOTS",
				"//api/providers/" + providerId + "/slots", "/api/providers/" + providerId + "/slots;x=1",
				"/api/providers/%2e%2e/providers/" + providerId + "/slots", "/api/providers/" + providerId + "/./slots",
				"/api/providers/" + providerId + "/%73lots")) {
			MockHttpServletResponse response = mvc.perform(get(URI.create(variant + query)).header(HttpHeaders.AUTHORIZATION, bearer)).andReturn().getResponse();
			// Quem chega ao controller (prestador inexistente) responde com o código do agendamento; o 429 e as recusas do
			// firewall/roteamento não têm esse código.
			if (response.getContentAsString().contains("PROVIDER_NOT_FOUND")) {
				reachedHandler.add(variant + " -> " + response.getStatus());
			}
		}

		assertTrue(reachedHandler.isEmpty(), "variações atendidas pelo controller com o limite esgotado: " + reachedHandler);
	}

	@Test
	void cabecalhosDeProxyEnviadosPeloClienteNaoMudamAChaveDoLimite() throws Exception {
		seedUser("maria@exemplo.test", "CUSTOMER", true);
		// Sem proxy configurado (padrão), a chave é o endereço da CONEXÃO: X-Forwarded-For / Forwarded forjados não trocam de chave.
		for (int i = 0; i < 3; i++) {
			assertEquals(200, mvc.perform(post("/api/auth/login").with(from("10.0.0.1")).header("X-Forwarded-For", "203.0.113." + i)
					.header("X-Real-IP", "198.51.100." + i).header("Forwarded", "for=192.0.2." + i)
					.contentType(MediaType.APPLICATION_JSON).content(json("email", "maria@exemplo.test", "password", PASSWORD)))
					.andReturn().getResponse().getStatus());
		}

		MockHttpServletResponse response = mvc.perform(post("/api/auth/login").with(from("10.0.0.1")).header("X-Forwarded-For", "203.0.113.200")
				.header("Forwarded", "for=192.0.2.200").contentType(MediaType.APPLICATION_JSON)
				.content(json("email", "maria@exemplo.test", "password", PASSWORD))).andReturn().getResponse();

		assertEquals(429, response.getStatus(), "o cabeçalho forjado não deu uma chave nova");
		assertFalse(response.getContentAsString().contains("203.0.113"), "a resposta não repete cabeçalhos do cliente");
	}
}
