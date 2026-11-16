package com.queuecare.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Espera LIMITADA pelos bloqueios de pets (ADR 0016, item 5): se outra transação segura a linha do pet ou a do cliente por mais que o
 * limite, a API responde 409 RESOURCE_BUSY (nunca 500, nunca espera infinita) e funciona assim que o bloqueio cai. Leituras comuns
 * não esperam por bloqueio de linha.
 */
@TestPropertySource(properties = "queuecare.security.user-lock-timeout=400ms")
class PetLockTimeoutIntegrationTest extends AuthIntegrationTestBase {

	@Autowired
	DataSource dataSource;

	UUID owner;
	String token;

	private void login() throws Exception {
		owner = seedUser("tutor-l@exemplo.test", "CUSTOMER", true);
		String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(json("email", "tutor-l@exemplo.test", "password", PASSWORD)))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		token = "Bearer " + JsonPath.read(body, "$.accessToken");
	}

	private ResultActions send(MockHttpServletRequestBuilder builder, String content) throws Exception {
		builder = builder.header(HttpHeaders.AUTHORIZATION, token);
		if (content != null) {
			builder = builder.contentType(MediaType.APPLICATION_JSON).content(content);
		}
		return mvc.perform(builder);
	}

	private static final String BODY = "{\"name\":\"Rex\",\"species\":\"DOG\"}";

	@Test
	void bloqueioDaLinhaDoPetViraConflito409RapidoESoltaDepois() throws Exception {
		login();
		String id = JsonPath.read(send(post("/api/customer/pets"), BODY).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");

		try (Connection holder = dataSource.getConnection()) {
			holder.setAutoCommit(false);
			try (var stmt = holder.createStatement()) {
				stmt.execute("SELECT id FROM pets FOR NO KEY UPDATE"); // por exemplo, uma reserva em andamento
			}
			long start = System.nanoTime();
			send(put("/api/customer/pets/" + id), BODY).andExpect(status().isConflict()).andExpect(jsonPath("$.reason").value("RESOURCE_BUSY"));
			long elapsedMillis = (System.nanoTime() - start) / 1_000_000;
			assertTrue(elapsedMillis < 5_000, "a espera é limitada (~400 ms), levou " + elapsedMillis + " ms");
			send(post("/api/customer/pets/" + id + "/archive"), null).andExpect(status().isConflict()).andExpect(jsonPath("$.reason").value("RESOURCE_BUSY"));
			send(post("/api/customer/pets/" + id + "/reactivate"), null).andExpect(status().isConflict()).andExpect(jsonPath("$.reason").value("RESOURCE_BUSY"));
			// leitura comum não espera pelo bloqueio de linha
			send(get("/api/customer/pets/" + id), null).andExpect(status().isOk());
			send(get("/api/customer/pets"), null).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
			holder.rollback();
		}

		send(post("/api/customer/pets/" + id + "/archive"), null).andExpect(status().isOk()).andExpect(jsonPath("$.active").value(false));
		assertEquals("0", jdbc.queryForObject("SHOW lock_timeout", String.class), "o limite vale só dentro da transação, não vaza para a conexão do pool");
	}

	@Test
	void bloqueioDaLinhaDoClienteVoltaConflito409AoCriarESoltaDepois() throws Exception {
		login();
		try (Connection holder = dataSource.getConnection()) {
			holder.setAutoCommit(false);
			try (var stmt = holder.createStatement()) {
				stmt.execute("SELECT id FROM users WHERE email = 'tutor-l@exemplo.test' FOR NO KEY UPDATE");
			}
			send(post("/api/customer/pets"), BODY).andExpect(status().isConflict()).andExpect(jsonPath("$.reason").value("RESOURCE_BUSY"));
			assertEquals(0L, count("SELECT count(*) FROM pets"), "nada ficou pela metade");
			holder.rollback();
		}
		send(post("/api/customer/pets"), BODY).andExpect(status().isCreated());
		assertEquals(1L, count("SELECT count(*) FROM pets WHERE customer_id = ?", owner));
	}
}
