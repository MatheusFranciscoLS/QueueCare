package com.queuecare.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.sql.Connection;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;

/**
 * Espera LIMITADA da remarcação (ADR 0017): se outra transação segura a linha do agendamento, a remarcação não espera para sempre:
 * estourado o limite responde 409 RESOURCE_BUSY (nunca 500, nunca espera infinita) e funciona assim que o bloqueio cai.
 */
@TestPropertySource(properties = "queuecare.security.user-lock-timeout=400ms")
class RescheduleLockTimeoutIntegrationTest extends AuthIntegrationTestBase {

	static final ZoneId ZONE = ZoneId.of("America/Sao_Paulo");

	@Autowired
	DataSource dataSource;

	@Test
	void linhaBloqueadaViraConflito409RapidoESoltaDepois() throws Exception {
		UUID tutor = seedUser("tutor-l@exemplo.test", "CUSTOMER", true);
		UUID owner = seedUser("prestador-l@exemplo.test", "PROVIDER", true);
		UUID provider = jdbc.queryForObject("INSERT INTO providers (user_id, business_name, time_zone, minimum_notice_minutes, cancellation_deadline_minutes) "
				+ "VALUES (?, 'Estúdio', 'America/Sao_Paulo', 60, 120) RETURNING id", UUID.class, owner);
		UUID service = jdbc.queryForObject("INSERT INTO services (provider_id, name, duration_minutes) VALUES (?, 'Banho', 60) RETURNING id", UUID.class, provider);
		for (int day = 1; day <= 7; day++) {
			jdbc.update("INSERT INTO availability_windows (provider_id, day_of_week, start_time, end_time) VALUES (?, ?, '08:00', '12:00')", provider, day);
		}
		LocalDate base = LocalDate.now(ZONE).plusDays(7);
		Timestamp start = Timestamp.from(LocalDateTime.of(base, java.time.LocalTime.of(9, 0)).atZone(ZONE).toInstant());
		UUID id = jdbc.queryForObject("INSERT INTO appointments (provider_id, service_id, customer_id, starts_at, ends_at, status, pet_id, pet_name, pet_species) "
				+ "VALUES (?, ?, ?, ?, ?, 'SCHEDULED', ?, 'Rex', 'DOG') RETURNING id", UUID.class, provider, service, tutor, start,
				Timestamp.from(start.toInstant().plusSeconds(3600)), petOf(tutor));
		String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(json("email", "tutor-l@exemplo.test", "password", PASSWORD)))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		String token = "Bearer " + JsonPath.read(body, "$.accessToken");
		String move = "{\"localStart\":\"" + base.plusDays(1) + "T09:00:00\"}";

		try (Connection holder = dataSource.getConnection()) {
			holder.setAutoCommit(false);
			try (var st = holder.prepareStatement("SELECT id FROM appointments WHERE id = ? FOR NO KEY UPDATE")) {
				st.setObject(1, id);
				st.executeQuery(); // por exemplo, um check-in em andamento
			}
			long begin = System.nanoTime();
			mvc.perform(post("/api/appointments/" + id + "/reschedule").header(HttpHeaders.AUTHORIZATION, token).contentType(MediaType.APPLICATION_JSON).content(move))
					.andExpect(status().isConflict()).andExpect(jsonPath("$.reason").value("RESOURCE_BUSY"));
			assertTrue((System.nanoTime() - begin) / 1_000_000 < 5_000, "a espera é limitada (~400 ms)");
			assertEquals(0L, count("SELECT count(*) FROM appointment_events WHERE event_type = 'RESCHEDULED'"), "nada ficou pela metade");
			holder.rollback();
		}

		mvc.perform(post("/api/appointments/" + id + "/reschedule").header(HttpHeaders.AUTHORIZATION, token).contentType(MediaType.APPLICATION_JSON).content(move))
				.andExpect(status().isOk());
		assertEquals("0", jdbc.queryForObject("SHOW lock_timeout", String.class), "o limite vale só dentro da transação, não vaza para a conexão do pool");
	}
}
