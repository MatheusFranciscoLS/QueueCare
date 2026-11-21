package com.queuecare.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

/**
 * Identificador de requisição (ADR 0029): toda resposta devolve {@code X-Request-Id}, o mesmo código está no MDC durante o tratamento (e portanto em cada linha de log),
 * um código do cliente só vale se for seguro, e o MDC não vaza para a chamada seguinte.
 */
class RequestIdIntegrationTest extends AuthIntegrationTestBase {

	private String idOf(org.springframework.test.web.servlet.ResultActions result) {
		return result.andReturn().getResponse().getHeader("X-Request-Id");
	}

	@Test
	void todaRespostaTemOCodigoInclusiveErrosDeSegurancaERotaInexistente() throws Exception {
		String ok = idOf(mvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk()).andExpect(header().exists("X-Request-Id")));
		assertTrue(ok.matches("[0-9a-f]{16}"), "código gerado: 16 caracteres hexadecimais: " + ok);

		mvc.perform(get("/api/test/staff")).andExpect(status().isUnauthorized()).andExpect(header().exists("X-Request-Id")); // sem token: barrado pela segurança
		mvc.perform(get("/api/rota-que-nao-existe")).andExpect(header().exists("X-Request-Id"));
		mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isBadRequest()).andExpect(header().exists("X-Request-Id"));
	}

	@Test
	void cadaChamadaTemUmCodigoDiferente() throws Exception {
		String first = idOf(mvc.perform(get("/actuator/health/liveness")));
		String second = idOf(mvc.perform(get("/actuator/health/liveness")));
		assertNotEquals(first, second);
	}

	@Test
	void umCodigoSeguroDoClienteEhRepetidoParaLigarOsDoisLadosDoErro() throws Exception {
		mvc.perform(get("/actuator/health/liveness").header("X-Request-Id", "abc-123_DEF.456")).andExpect(header().string("X-Request-Id", "abc-123_DEF.456"));
		String sixtyFour = "a".repeat(64);
		mvc.perform(get("/actuator/health/liveness").header("X-Request-Id", sixtyFour)).andExpect(header().string("X-Request-Id", sixtyFour));
	}

	@Test
	void codigoInseguroOuForaDoTamanhoEhDescartadoEReposto() throws Exception {
		for (String bad : new String[] { "curto", "x".repeat(65), "com espaço no meio", "<script>alert(1)</script>", "aspas\"e'aspas1", "linha1;Set-Cookie=x1", "../../etc/passwd" }) {
			String id = idOf(mvc.perform(get("/actuator/health/liveness").header("X-Request-Id", bad)));
			assertNotEquals(bad, id, "descartado: " + bad);
			assertTrue(id.matches("[0-9a-f]{16}"), "no lugar vem um código novo: " + id);
		}
	}

	@Test
	void ocodigoEstaNoMdcDuranteOTratamentoEOMdcNaoVazaParaDepois() throws Exception {
		seedUser("rid-owner@exemplo.test", "PROVIDER", true);
		String login = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(json("email", "rid-owner@exemplo.test", "password", PASSWORD))).andReturn().getResponse()
				.getContentAsString();
		String bearer = "Bearer " + JsonPath.read(login, "$.accessToken");

		var result = mvc.perform(get("/api/test/mdc").header(HttpHeaders.AUTHORIZATION, bearer).header("X-Request-Id", "meu-codigo-123")).andExpect(status().isOk()).andReturn().getResponse();
		assertEquals("meu-codigo-123", result.getContentAsString(), "o código estava no MDC dentro do controlador");
		assertEquals("meu-codigo-123", result.getHeader("X-Request-Id"));
		assertNull(MDC.get("requestId"), "o MDC é limpo no fim da chamada");
	}

	@Test
	void umaOrigemPermitidaPodeLerOCodigoEOTempoDeEsperaDoLimite() throws Exception {
		mvc.perform(get("/actuator/health/liveness").header("Origin", "http://localhost:5173")).andExpect(status().isOk())
				.andExpect(header().string("Access-Control-Expose-Headers", org.hamcrest.Matchers.allOf(org.hamcrest.Matchers.containsString("X-Request-Id"),
						org.hamcrest.Matchers.containsString("Retry-After"))));
	}

}
