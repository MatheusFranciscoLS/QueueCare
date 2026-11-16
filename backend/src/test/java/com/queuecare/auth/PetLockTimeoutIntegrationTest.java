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

	@Test
	void reservaComPetBloqueadoViraConflito409RapidoESoltaDepois() throws Exception {
		login();
		UUID providerUser = seedUser("prestador-l@exemplo.test", "PROVIDER", true);
		UUID provider = jdbc.queryForObject("INSERT INTO providers (user_id, business_name, time_zone, minimum_notice_minutes, cancellation_deadline_minutes) "
				+ "VALUES (?, 'Estúdio', 'America/Sao_Paulo', 60, 120) RETURNING id", UUID.class, providerUser);
		UUID service = jdbc.queryForObject("INSERT INTO services (provider_id, name, duration_minutes) VALUES (?, 'Banho', 60) RETURNING id", UUID.class, provider);
		for (int day = 1; day <= 7; day++) {
			jdbc.update("INSERT INTO availability_windows (provider_id, day_of_week, start_time, end_time) VALUES (?, ?, '08:00', '12:00')", provider, day);
		}
		String pet = JsonPath.read(send(post("/api/customer/pets"), BODY).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
		String when = java.time.LocalDate.now(java.time.ZoneId.of("America/Sao_Paulo")).plusDays(7) + "T09:00:00";
		String booking = "{\"providerId\":\"" + provider + "\",\"serviceId\":\"" + service + "\",\"localStart\":\"" + when + "\",\"petId\":\"" + pet + "\"}";

		try (Connection holder = dataSource.getConnection()) {
			holder.setAutoCommit(false);
			try (var stmt = holder.createStatement()) {
				stmt.execute("SELECT id FROM pets FOR NO KEY UPDATE"); // por exemplo, o tutor está editando ou arquivando o pet
			}
			long start = System.nanoTime();
			send(post("/api/appointments"), booking).andExpect(status().isConflict()).andExpect(jsonPath("$.reason").value("RESOURCE_BUSY"));
			assertTrue((System.nanoTime() - start) / 1_000_000 < 5_000, "a espera é limitada (~400 ms)");
			assertEquals(0L, count("SELECT count(*) FROM appointments"), "nada ficou pela metade");
			holder.rollback();
		}
		send(post("/api/appointments"), booking).andExpect(status().isCreated());
		assertEquals(1L, count("SELECT count(*) FROM appointments WHERE pet_id = ?::uuid", pet));
	}
}
