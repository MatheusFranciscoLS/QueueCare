package com.queuecare.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.sql.Connection;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;

/** Espera LIMITADA pelo bloqueio da linha do usuário: outra transação segurando a linha vira 409 RESOURCE_BUSY (nunca 500 nem espera infinita). */
@TestPropertySource(properties = "queuecare.security.user-lock-timeout=400ms")
class PasswordLockTimeoutIntegrationTest extends AuthIntegrationTestBase {

	@Autowired
	DataSource dataSource;

	@Test
	void linhaDoUsuarioBloqueadaPorOutraTransacaoViraConflitoRapidoESoltaDepois() throws Exception {
		UUID id = seedUser("c@exemplo.test", "CUSTOMER", true);
		String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(json("email", "c@exemplo.test", "password", PASSWORD)))
				.andReturn().getResponse().getContentAsString();
		String token = "Bearer " + JsonPath.read(body, "$.accessToken");
		String payload = json("currentPassword", PASSWORD, "newPassword", "nova-senha-forte-456");

		try (Connection holder = dataSource.getConnection()) {
			holder.setAutoCommit(false);
			try (var stmt = holder.createStatement()) {
				stmt.execute("SELECT id FROM users WHERE id = '" + id + "' FOR UPDATE");
			}
			long start = System.nanoTime();
			mvc.perform(post("/api/auth/change-password").header(HttpHeaders.AUTHORIZATION, token).contentType(MediaType.APPLICATION_JSON).content(payload))
					.andExpect(status().isConflict()).andExpect(jsonPath("$.reason").value("RESOURCE_BUSY"));
			assertEquals(true, (System.nanoTime() - start) / 1_000_000 < 5_000, "espera limitada");
			holder.rollback();
		}

		mvc.perform(post("/api/auth/change-password").header(HttpHeaders.AUTHORIZATION, token).contentType(MediaType.APPLICATION_JSON).content(payload))
				.andExpect(status().isOk());
		assertEquals("0", jdbc.queryForObject("SHOW lock_timeout", String.class), "o limite não vaza para a conexão do pool");
	}
}
