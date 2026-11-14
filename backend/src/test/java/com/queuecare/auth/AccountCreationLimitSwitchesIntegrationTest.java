package com.queuecare.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Cada grupo de limites tem a SUA chave de ativação: desligar os limites por minuto ({@code enabled=false}) NÃO desliga o
 * limite por hora de criação de contas (proteção contra enumeração de e-mails), que só se desliga pela chave própria.
 */
@TestPropertySource(properties = {"queuecare.rate-limit.enabled=false", "queuecare.rate-limit.account-creations-per-hour=3",
		"queuecare.rate-limit.login-per-minute=2"})
class AccountCreationLimitSwitchesIntegrationTest extends AuthIntegrationTestBase {

	/** Corpo inválido (400 de validação): a tentativa conta no limite mesmo assim. */
	static final String BAD = "{\"name\":\"X\"}";

	int createProvider(String bearer) throws Exception {
		return mvc.perform(post("/api/admin/providers").header(HttpHeaders.AUTHORIZATION, bearer).contentType(MediaType.APPLICATION_JSON).content(BAD))
				.andReturn().getResponse().getStatus();
	}

	int login(String address) throws Exception {
		RequestPostProcessor from = r -> {
			r.setRemoteAddr(address);
			return r;
		};
		return mvc.perform(post("/api/auth/login").with(from).contentType(MediaType.APPLICATION_JSON)
				.content(json("email", "x@exemplo.test", "password", "qualquer-senha-1"))).andReturn().getResponse().getStatus();
	}

	@Test
	void limitesPorMinutoDesligadosMasLimiteDeContasContinuaAtivo() throws Exception {
		String admin = "Bearer " + validToken(seedUser("admin@exemplo.test", "ADMIN", true));
		for (int i = 0; i < 3; i++) {
			assertEquals(400, createProvider(admin));
		}
		assertEquals(429, createProvider(admin), "a 4ª tentativa é barrada mesmo com enabled=false");
		// e os limites por minuto seguem desligados (contrato de enabled): bem mais que 2 logins no mesmo endereço
		for (int i = 0; i < 6; i++) {
			assertTrue(login("10.0.0.1") != 429);
		}
	}
}

/** Chave própria desliga o limite de contas, e SÓ ele. */
@TestPropertySource(properties = {"queuecare.rate-limit.account-creations-enabled=false", "queuecare.rate-limit.account-creations-per-hour=2",
		"queuecare.rate-limit.login-per-minute=2"})
class AccountCreationLimitOffIntegrationTest extends AuthIntegrationTestBase {

	@Test
	void chaveProprioDesligaOLimiteDeContasMasNaoOsDemais() throws Exception {
		String admin = "Bearer " + validToken(seedUser("admin@exemplo.test", "ADMIN", true));
		for (int i = 0; i < 6; i++) {
			assertEquals(400, mvc.perform(post("/api/admin/providers").header(HttpHeaders.AUTHORIZATION, admin).contentType(MediaType.APPLICATION_JSON)
					.content(AccountCreationLimitSwitchesIntegrationTest.BAD)).andReturn().getResponse().getStatus());
		}
		RequestPostProcessor from = r -> {
			r.setRemoteAddr("10.0.0.3");
			return r;
		};
		int last = 0;
		for (int i = 0; i < 3; i++) {
			last = mvc.perform(post("/api/auth/login").with(from).contentType(MediaType.APPLICATION_JSON)
					.content(json("email", "x@exemplo.test", "password", "qualquer-senha-1"))).andReturn().getResponse().getStatus();
		}
		assertEquals(429, last, "o limite por minuto de login segue ligado");
	}
}

/** Sem mexer em nada, as duas proteções estão ativas. */
@TestPropertySource(properties = {"queuecare.rate-limit.account-creations-per-hour=2", "queuecare.rate-limit.login-per-minute=2"})
class AccountCreationLimitDefaultsIntegrationTest extends AuthIntegrationTestBase {

	@Test
	void porPadraoAsDuasProtecoesEstaoAtivas() throws Exception {
		String admin = "Bearer " + validToken(seedUser("admin@exemplo.test", "ADMIN", true));
		int[] statuses = new int[3];
		for (int i = 0; i < 3; i++) {
			statuses[i] = mvc.perform(post("/api/admin/providers").header(HttpHeaders.AUTHORIZATION, admin).contentType(MediaType.APPLICATION_JSON)
					.content(AccountCreationLimitSwitchesIntegrationTest.BAD)).andReturn().getResponse().getStatus();
		}
		assertEquals(400, statuses[0]);
		assertEquals(400, statuses[1]);
		assertEquals(429, statuses[2]);
		RequestPostProcessor from = r -> {
			r.setRemoteAddr("10.0.0.2");
			return r;
		};
		int last = 0;
		for (int i = 0; i < 3; i++) {
			last = mvc.perform(post("/api/auth/login").with(from).contentType(MediaType.APPLICATION_JSON)
					.content(json("email", "x@exemplo.test", "password", "qualquer-senha-1"))).andReturn().getResponse().getStatus();
		}
		assertEquals(429, last);
	}
}
