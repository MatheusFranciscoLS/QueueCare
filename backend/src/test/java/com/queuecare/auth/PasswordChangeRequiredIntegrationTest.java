package com.queuecare.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Conta com senha inicial (must_change_password): só GET /api/me e a troca de senha. TODA outra rota autenticada, para todos
 * os perfis, inclusive as que exigem apenas "estar logado", responde 403 PASSWORD_CHANGE_REQUIRED. O estado vem do banco a
 * cada requisição: vale na hora, sem depender do token.
 */
class PasswordChangeRequiredIntegrationTest extends AuthIntegrationTestBase {

	private ResultActions send(MockHttpServletRequestBuilder builder, String bearer) throws Exception {
		return mvc.perform(builder.header(HttpHeaders.AUTHORIZATION, bearer).contentType(MediaType.APPLICATION_JSON).content("{}"));
	}

	private void assertBlocked(ResultActions result) throws Exception {
		result.andExpect(status().isForbidden()).andExpect(jsonPath("$.reason").value("PASSWORD_CHANGE_REQUIRED"));
	}

	@Test
	void todosOsPerfisComTrocaPendenteSoAcessamMeETrocaDeSenha() throws Exception {
		String someId = UUID.randomUUID().toString();
		for (String role : List.of("ADMIN", "PROVIDER", "ATTENDANT", "CUSTOMER")) {
			String bearer = "Bearer " + validToken(seedUser(role.toLowerCase() + "@exemplo.test", role, true, true));

			send(get("/api/me"), bearer).andExpect(status().isOk()).andExpect(jsonPath("$.mustChangePassword").value(true));

			assertBlocked(send(get("/api/agenda").param("date", "2030-01-01"), bearer));
			assertBlocked(send(get("/api/appointments/mine"), bearer));
			assertBlocked(send(post("/api/appointments"), bearer));
			assertBlocked(send(post("/api/appointments/" + someId + "/cancel"), bearer));
			assertBlocked(send(get("/api/providers/" + someId + "/slots").param("serviceId", someId).param("date", "2030-01-01"), bearer));
			assertBlocked(send(get("/api/provider"), bearer));
			assertBlocked(send(put("/api/provider/availability"), bearer));
			assertBlocked(send(get("/api/provider/staff"), bearer));
			assertBlocked(send(delete("/api/provider/staff/" + someId), bearer));
			assertBlocked(send(post("/api/provider/staff/accounts"), bearer));
			assertBlocked(send(post("/api/admin/providers"), bearer));
			// rotas que nem existem também não vazam: o bloqueio vem antes do roteamento
			assertBlocked(send(get("/api/qualquer-coisa"), bearer));
		}
	}

	@Test
	void variacoesDeCaminhoDeMeETrocaDeSenhaNaoAbremPassagem() throws Exception {
		String bearer = "Bearer " + validToken(seedUser("c@exemplo.test", "CUSTOMER", true, true));

		for (String path : List.of("/api/me/", "/API/ME", "//api/me", "/api/me;x=1", "/api/./me", "/api/me%2F", "/api/me.json")) {
			int status = mvc.perform(get(URI.create(path)).header(HttpHeaders.AUTHORIZATION, bearer)).andReturn().getResponse().getStatus();
			// Ou o firewall recusa a variação (400), ou o filtro a trata como outra rota (403); nunca 200.
			org.junit.jupiter.api.Assertions.assertNotEquals(200, status, path);
		}
		// Método errado na rota liberada também não passa (só POST em change-password, só GET em /me).
		assertBlocked(send(post("/api/me"), bearer));
		assertBlocked(send(get("/api/auth/change-password"), bearer));
	}

	@Test
	void trocaDeSenhaNaoEBloqueadaPeloFiltroENecessitaAutenticacao() throws Exception {
		String bearer = "Bearer " + validToken(seedUser("c@exemplo.test", "CUSTOMER", true, true));

		// A rota em si chega no PR seguinte; aqui só provamos que o filtro a deixa passar (não é 403 PASSWORD_CHANGE_REQUIRED)...
		int status = send(post("/api/auth/change-password"), bearer).andReturn().getResponse().getStatus();
		org.junit.jupiter.api.Assertions.assertNotEquals(403, status);
		// ...e que sem token continua exigindo autenticação.
		mvc.perform(post("/api/auth/change-password").contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isUnauthorized());
	}

	@Test
	void oEstadoVemDoBancoACadaRequisicaoComOMesmoToken() throws Exception {
		UUID id = seedUser("c@exemplo.test", "CUSTOMER", true, true);
		String bearer = "Bearer " + validToken(id);
		assertBlocked(send(get("/api/appointments/mine"), bearer));

		jdbc.update("UPDATE users SET must_change_password = false WHERE id = ?", id);
		send(get("/api/appointments/mine"), bearer).andExpect(status().isOk());

		jdbc.update("UPDATE users SET must_change_password = true WHERE id = ?", id);
		assertBlocked(send(get("/api/appointments/mine"), bearer));
	}

	@Test
	void contaSemPendenciaNaoE_afetada() throws Exception {
		String bearer = "Bearer " + validToken(seedUser("c@exemplo.test", "CUSTOMER", true, false));

		send(get("/api/appointments/mine"), bearer).andExpect(status().isOk());
	}
}
