package com.queuecare.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.TestPropertySource;

/**
 * Quem cria contas para outras pessoas (ADMIN e prestador) não descobre QUEM é o dono de um e-mail já usado: o conflito é uma
 * resposta única, igual para qualquer perfil da conta existente (ativa ou não); e as tentativas são limitadas por usuário.
 */
@TestPropertySource(properties = "queuecare.rate-limit.account-creations-per-hour=4")
class AccountConflictAndRateLimitIntegrationTest extends AuthIntegrationTestBase {

	private String admin;
	private String provider;

	@BeforeEach
	void seed() {
		admin = "Bearer " + validToken(seedUser("admin@exemplo.test", "ADMIN", true));
		UUID providerUser = seedUser("prestador@exemplo.test", "PROVIDER", true);
		jdbc.update("INSERT INTO providers (user_id, business_name, time_zone) VALUES (?, 'Oficina', 'America/Sao_Paulo')", providerUser);
		provider = "Bearer " + validToken(providerUser);
	}

	private MockHttpServletResponse createProvider(String email) throws Exception {
		return mvc.perform(post("/api/admin/providers").header(HttpHeaders.AUTHORIZATION, admin).contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\":\"X\",\"email\":\"" + email + "\",\"initialPassword\":\"senha-inicial-123\",\"businessName\":\"N\",\"timeZone\":\"America/Sao_Paulo\","
						+ "\"minimumNoticeMinutes\":0,\"cancellationDeadlineMinutes\":0}")).andReturn().getResponse();
	}

	private MockHttpServletResponse createAttendant(String email) throws Exception {
		return mvc.perform(post("/api/provider/staff/accounts").header(HttpHeaders.AUTHORIZATION, provider).contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\":\"X\",\"email\":\"" + email + "\",\"initialPassword\":\"senha-inicial-123\"}")).andReturn().getResponse();
	}

	@Test
	void conflitoEIgualParaQualquerPerfilEstadoOuCaixaDoEmailExistente() throws Exception {
		seedUser("cliente@exemplo.test", "CUSTOMER", true);
		seedUser("outro-prestador@exemplo.test", "PROVIDER", true);
		seedUser("atendente@exemplo.test", "ATTENDANT", true);
		seedUser("outro-admin@exemplo.test", "ADMIN", true);
		seedUser("inativo@exemplo.test", "CUSTOMER", false);

		List<String> providerBodies = new ArrayList<>();
		List<String> attendantBodies = new ArrayList<>();
		for (String email : List.of("cliente@exemplo.test", "outro-prestador@exemplo.test", "atendente@exemplo.test", "outro-admin@exemplo.test",
				"inativo@exemplo.test", "CLIENTE@exemplo.test", "admin@exemplo.test")) {
			requestLimiter.clearAll(); // aqui o assunto é o conteúdo da resposta, não o limite por hora
			MockHttpServletResponse viaAdmin = createProvider(email);
			MockHttpServletResponse viaProvider = createAttendant(email);
			assertEquals(409, viaAdmin.getStatus(), email);
			assertEquals(409, viaProvider.getStatus(), email);
			providerBodies.add(viaAdmin.getContentAsString());
			attendantBodies.add(viaProvider.getContentAsString());
		}

		assertEquals(1, providerBodies.stream().distinct().count(), "o corpo do conflito é o mesmo para qualquer conta existente: " + providerBodies);
		assertEquals(1, attendantBodies.stream().distinct().count(), attendantBodies.toString());
		String body = providerBodies.get(0);
		assertTrue(body.contains("ACCOUNT_CONFLICT"));
		assertFalse(body.toLowerCase().contains("cadastrad") || body.contains("CUSTOMER") || body.contains("PROVIDER") || body.contains("ATTENDANT") || body.contains("ADMIN")
				|| body.contains("@"), "nem confirma que o e-mail existe nem diz o perfil: " + body);
	}

	@Test
	void cadastroPublicoSegueComSuaMensagemPropria() throws Exception {
		seedUser("cliente@exemplo.test", "CUSTOMER", true);

		MockHttpServletResponse response = mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
				.content(json("name", "Fulano", "email", "cliente@exemplo.test", "password", PASSWORD))).andReturn().getResponse();

		assertEquals(409, response.getStatus());
		assertNotEquals(createProvider("cliente@exemplo.test").getContentAsString(), response.getContentAsString());
	}

	@Test
	void tentativasDeCriarContaSaoLimitadasPorUsuarioComOuSemSucesso() throws Exception {
		seedUser("existente@exemplo.test", "CUSTOMER", true);

		assertEquals(201, createProvider("novo1@exemplo.test").getStatus());
		assertEquals(409, createProvider("existente@exemplo.test").getStatus());
		assertEquals(400, createProvider("nao-e-email").getStatus(), "tentativa inválida também conta");
		assertEquals(201, createProvider("novo2@exemplo.test").getStatus());

		MockHttpServletResponse limited = createProvider("novo3@exemplo.test");
		assertEquals(429, limited.getStatus());
		assertTrue(Long.parseLong(limited.getHeader(HttpHeaders.RETRY_AFTER)) > 60, "janela de uma hora: " + limited.getHeader(HttpHeaders.RETRY_AFTER));
		assertEquals(0L, count("SELECT count(*) FROM users WHERE email = 'novo3@exemplo.test'"));
		// O limite é por usuário: o prestador tem o próprio contador, e outro ADMIN também.
		assertEquals(201, createAttendant("atendente1@exemplo.test").getStatus());
	}
}
