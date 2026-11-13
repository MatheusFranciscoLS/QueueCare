package com.queuecare.auth;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/** Validação do JWT, rotas protegidas, perfis (R4), CORS e rotas públicas, com os filtros reais do Spring Security. */
class JwtSecurityIntegrationTest extends AuthIntegrationTestBase {

	private ResultActions me(String token) throws Exception {
		return mvc.perform(get("/api/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
	}

	private String loginToken(String email) throws Exception {
		String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(json("email", email, "password", PASSWORD)))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		return JsonPath.read(body, "$.accessToken");
	}

	// --- identidade ---

	@Test
	void semTokenEhNaoAutenticado() throws Exception {
		mvc.perform(get("/api/me")).andExpect(status().isUnauthorized());
	}

	@Test
	void tokenValidIdentificaOUsuarioEOPerfilVemDoBanco() throws Exception {
		UUID id = seedUser("maria@exemplo.test", "CUSTOMER", true);

		me(loginToken("maria@exemplo.test")).andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(id.toString()))
				.andExpect(jsonPath("$.role").value("CUSTOMER"))
				.andExpect(content().string(not(containsString("$2"))));
	}

	@Test
	void perfilDoTokenNaoMandaOPerfilVemDoBanco() throws Exception {
		UUID id = seedUser("maria@exemplo.test", "CUSTOMER", true);
		Instant now = Instant.now();
		String claimsProvider = token(TEST_SECRET, ISSUER, AUDIENCE, id.toString(), now, now.plusSeconds(900), "PROVIDER");

		me(claimsProvider).andExpect(status().isOk()).andExpect(jsonPath("$.role").value("CUSTOMER"));
		mvc.perform(get("/api/test/provider-only").header(HttpHeaders.AUTHORIZATION, "Bearer " + claimsProvider)).andExpect(status().isForbidden());
	}

	// --- validação do token ---

	@Test
	void tokenExpiradoEhRecusado() throws Exception {
		UUID id = seedUser("maria@exemplo.test", "CUSTOMER", true);
		Instant now = Instant.now();

		me(token(TEST_SECRET, ISSUER, AUDIENCE, id.toString(), now.minusSeconds(3600), now.minusSeconds(300), "CUSTOMER")).andExpect(status().isUnauthorized());
	}

	@Test
	void tokenExpiradoHaPoucoDentroDaToleranciaDeRelogioEhAceito() throws Exception {
		UUID id = seedUser("maria@exemplo.test", "CUSTOMER", true);
		Instant now = Instant.now();

		me(token(TEST_SECRET, ISSUER, AUDIENCE, id.toString(), now.minusSeconds(900), now.minusSeconds(10), "CUSTOMER")).andExpect(status().isOk());
	}

	@Test
	void assinaturaDeOutraChaveEhRecusada() throws Exception {
		UUID id = seedUser("maria@exemplo.test", "CUSTOMER", true);
		Instant now = Instant.now();

		me(token(OTHER_SECRET, ISSUER, AUDIENCE, id.toString(), now, now.plusSeconds(900), "CUSTOMER")).andExpect(status().isUnauthorized());
	}

	@Test
	void tokenAdulteradoEhRecusado() throws Exception {
		UUID id = seedUser("maria@exemplo.test", "CUSTOMER", true);
		String valid = validToken(id);
		String[] parts = valid.split("\\.");
		String tamperedSignature = parts[0] + "." + parts[1] + "." + parts[2].substring(0, parts[2].length() - 3) + "AAA";

		me(tamperedSignature).andExpect(status().isUnauthorized());
		me("isto.nao.eh-um-jwt").andExpect(status().isUnauthorized());
		me("lixo").andExpect(status().isUnauthorized());
	}

	@Test
	void tokenSemAssinaturaAlgNoneEhRecusado() throws Exception {
		UUID id = seedUser("maria@exemplo.test", "CUSTOMER", true);

		me(unsignedToken(id)).andExpect(status().isUnauthorized());
	}

	@Test
	void emissorOuAudienciaIncorretosSaoRecusados() throws Exception {
		UUID id = seedUser("maria@exemplo.test", "CUSTOMER", true);
		Instant now = Instant.now();

		me(token(TEST_SECRET, "outro-emissor", AUDIENCE, id.toString(), now, now.plusSeconds(900), "CUSTOMER")).andExpect(status().isUnauthorized());
		me(token(TEST_SECRET, ISSUER, "outra-api", id.toString(), now, now.plusSeconds(900), "CUSTOMER")).andExpect(status().isUnauthorized());
		me(token(TEST_SECRET, ISSUER, null, id.toString(), now, now.plusSeconds(900), "CUSTOMER")).andExpect(status().isUnauthorized());
	}

	@Test
	void tokenSemExpEhRecusado() throws Exception {
		UUID id = seedUser("maria@exemplo.test", "CUSTOMER", true);

		// Sem exp o token valeria para sempre. (O Spring preenche iat sozinho quando ele falta; o que protege é exigir e validar exp.)
		me(token(TEST_SECRET, ISSUER, AUDIENCE, id.toString(), Instant.now(), null, "CUSTOMER")).andExpect(status().isUnauthorized());
	}

	@Test
	void subjectInvalidoOuAusenteEhRecusado() throws Exception {
		Instant now = Instant.now();

		me(token(TEST_SECRET, ISSUER, AUDIENCE, "nao-eh-uuid", now, now.plusSeconds(900), "CUSTOMER")).andExpect(status().isUnauthorized());
		me(token(TEST_SECRET, ISSUER, AUDIENCE, null, now, now.plusSeconds(900), "CUSTOMER")).andExpect(status().isUnauthorized());
	}

	@Test
	void iatNoFuturoOuPosteriorAExpEhRecusado() throws Exception {
		UUID id = seedUser("maria@exemplo.test", "CUSTOMER", true);
		Instant now = Instant.now();

		me(token(TEST_SECRET, ISSUER, AUDIENCE, id.toString(), now.plusSeconds(600), now.plusSeconds(1500), "CUSTOMER")).andExpect(status().isUnauthorized());
		String iatDepoisDeExp = "{\"iss\":\"" + ISSUER + "\",\"aud\":\"" + AUDIENCE + "\",\"sub\":\"" + id + "\",\"iat\":" + now.getEpochSecond() + ",\"exp\":" + (now.getEpochSecond() - 5) + "}";
		me(rawSignedToken(TEST_SECRET, iatDepoisDeExp)).andExpect(status().isUnauthorized());
		// iat levemente no futuro, dentro da tolerância de 30 s, é aceito.
		me(token(TEST_SECRET, ISSUER, AUDIENCE, id.toString(), now.plusSeconds(5), now.plusSeconds(905), "CUSTOMER")).andExpect(status().isOk());
	}

	// --- cabeçalho Authorization malformado ---

	@Test
	void esquemaBasicOuBearerVazioNaoAutentica() throws Exception {
		UUID id = seedUser("maria@exemplo.test", "CUSTOMER", true);
		String basic = java.util.Base64.getEncoder().encodeToString(("maria@exemplo.test:" + PASSWORD).getBytes(java.nio.charset.StandardCharsets.UTF_8));

		mvc.perform(get("/api/me").header(HttpHeaders.AUTHORIZATION, "Basic " + basic)).andExpect(status().isUnauthorized());
		mvc.perform(get("/api/me").header(HttpHeaders.AUTHORIZATION, "Bearer ")).andExpect(status().isUnauthorized());
		mvc.perform(get("/api/me").header(HttpHeaders.AUTHORIZATION, "Bearer")).andExpect(status().isUnauthorized());
		mvc.perform(get("/api/me").header(HttpHeaders.AUTHORIZATION, "bearer " + validToken(id))).andExpect(status().isOk()); // esquema não diferencia maiúsculas (RFC 6750)
	}

	@Test
	void tokenNaQueryStringNaoAutentica() throws Exception {
		UUID id = seedUser("maria@exemplo.test", "CUSTOMER", true);

		mvc.perform(get("/api/me").param("access_token", validToken(id))).andExpect(status().isUnauthorized());
	}

	@Test
	void doisCabecalhosAuthorizationNaoSaoAceitosEmSilencio() throws Exception {
		UUID id = seedUser("maria@exemplo.test", "CUSTOMER", true);

		mvc.perform(get("/api/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + validToken(id)).header(HttpHeaders.AUTHORIZATION, "Bearer lixo"))
				.andExpect(status().isUnauthorized());
		mvc.perform(get("/api/me").header(HttpHeaders.AUTHORIZATION, "Bearer lixo").header(HttpHeaders.AUTHORIZATION, "Bearer " + validToken(id)))
				.andExpect(status().isUnauthorized());
	}

	// --- usuário depois de emitido o token ---

	@Test
	void usuarioDesativadoComTokenAindaValidoEhRecusadoNaHora() throws Exception {
		UUID id = seedUser("maria@exemplo.test", "CUSTOMER", true);
		String token = loginToken("maria@exemplo.test");
		me(token).andExpect(status().isOk());

		jdbc.update("UPDATE users SET active = false WHERE id = ?", id);

		me(token).andExpect(status().isUnauthorized());
	}

	@Test
	void tokenDeUsuarioInexistenteEhRecusado() throws Exception {
		me(validToken(UUID.randomUUID())).andExpect(status().isUnauthorized());
	}

	// --- perfis (R4) ---

	@Test
	void cadaPerfilSoAcessaAsRotasPermitidas() throws Exception {
		seedUser("cliente@exemplo.test", "CUSTOMER", true);
		seedUser("atendente@exemplo.test", "ATTENDANT", true);
		seedUser("prestador@exemplo.test", "PROVIDER", true);
		String customer = "Bearer " + loginToken("cliente@exemplo.test");
		String attendant = "Bearer " + loginToken("atendente@exemplo.test");
		String provider = "Bearer " + loginToken("prestador@exemplo.test");

		mvc.perform(get("/api/test/provider-only").header(HttpHeaders.AUTHORIZATION, customer)).andExpect(status().isForbidden());
		mvc.perform(get("/api/test/provider-only").header(HttpHeaders.AUTHORIZATION, attendant)).andExpect(status().isForbidden());
		mvc.perform(get("/api/test/provider-only").header(HttpHeaders.AUTHORIZATION, provider)).andExpect(status().isOk());
		mvc.perform(get("/api/test/staff").header(HttpHeaders.AUTHORIZATION, customer)).andExpect(status().isForbidden());
		mvc.perform(get("/api/test/staff").header(HttpHeaders.AUTHORIZATION, attendant)).andExpect(status().isOk());
		mvc.perform(get("/api/test/staff").header(HttpHeaders.AUTHORIZATION, provider)).andExpect(status().isOk());
		mvc.perform(get("/api/test/provider-only")).andExpect(status().isUnauthorized());
	}

	// --- rotas públicas, padrão fechado, CORS e cabeçalhos ---

	@Test
	void saudeDaAplicacaoEPublicaEOrestoExigeToken() throws Exception {
		mvc.perform(get("/actuator/health")).andExpect(status().isOk());
		mvc.perform(get("/api/qualquer-rota-nova")).andExpect(status().isUnauthorized());
		mvc.perform(get("/actuator/env")).andExpect(status().isUnauthorized());
	}

	@Test
	void corsPermiteSoAOrigemConfigurada() throws Exception {
		mvc.perform(options("/api/auth/login")
				.header(HttpHeaders.ORIGIN, "http://localhost:5173")
				.header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
				.header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "content-type"))
				.andExpect(status().isOk())
				.andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:5173"));
		mvc.perform(options("/api/auth/login")
				.header(HttpHeaders.ORIGIN, "http://site-malicioso.test")
				.header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"))
				.andExpect(status().isForbidden());
	}

	@Test
	void respostasNaoCriamSessaoNemCookie() throws Exception {
		seedUser("maria@exemplo.test", "CUSTOMER", true);

		mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(json("email", "maria@exemplo.test", "password", PASSWORD)))
				.andExpect(status().isOk())
				.andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));
	}
}
