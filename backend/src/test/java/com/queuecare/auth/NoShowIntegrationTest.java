package com.queuecare.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/**
 * "Faltou" (ADR 0021, fatia 2): estado final, só a equipe do prestador, só de quem estava agendado e só a partir do horário marcado. O
 * evento fica no histórico, e a agenda do dia mostra quantas vezes o tutor faltou NAQUELE estúdio (nunca em outro).
 */
class NoShowIntegrationTest extends AuthIntegrationTestBase {

	private static final ZoneId SP = ZoneId.of("America/Sao_Paulo");

	private int seq;
	UUID ownerA, ownerB, attendantA, customer, otherCustomer;
	UUID providerA, providerB, serviceA, serviceB;
	String tokenA, tokenB, tokenAttendant, tokenCustomer;

	@BeforeEach
	void seed() throws Exception {
		ownerA = seedUser("na@exemplo.test", "PROVIDER", true);
		ownerB = seedUser("nb@exemplo.test", "PROVIDER", true);
		attendantA = seedUser("nat@exemplo.test", "ATTENDANT", true);
		customer = seedUser("nc@exemplo.test", "CUSTOMER", true);
		otherCustomer = seedUser("no@exemplo.test", "CUSTOMER", true);
		providerA = jdbc.queryForObject("INSERT INTO providers (user_id, business_name, time_zone, minimum_notice_minutes, cancellation_deadline_minutes) "
				+ "VALUES (?, 'Negócio A', 'America/Sao_Paulo', 0, 120) RETURNING id", UUID.class, ownerA);
		providerB = jdbc.queryForObject("INSERT INTO providers (user_id, business_name, time_zone, minimum_notice_minutes, cancellation_deadline_minutes) "
				+ "VALUES (?, 'Negócio B', 'America/Sao_Paulo', 0, 120) RETURNING id", UUID.class, ownerB);
		serviceA = jdbc.queryForObject("INSERT INTO services (provider_id, name, duration_minutes) VALUES (?, 'Banho', 30) RETURNING id", UUID.class, providerA);
		serviceB = jdbc.queryForObject("INSERT INTO services (provider_id, name, duration_minutes) VALUES (?, 'Banho', 30) RETURNING id", UUID.class, providerB);
		tokenA = tokenOf("na@exemplo.test");
		tokenB = tokenOf("nb@exemplo.test");
		tokenAttendant = tokenOf("nat@exemplo.test");
		tokenCustomer = tokenOf("nc@exemplo.test");
		mvc.perform(post("/api/provider/staff").header(HttpHeaders.AUTHORIZATION, tokenA).contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"nat@exemplo.test\"}"))
				.andExpect(status().isCreated());
	}

	private String tokenOf(String email) throws Exception {
		String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(json("email", email, "password", PASSWORD)))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		return "Bearer " + JsonPath.read(body, "$.accessToken");
	}

	private ResultActions noShow(String bearer, String id) throws Exception {
		return mvc.perform(post("/api/appointments/" + id + "/no-show").header(HttpHeaders.AUTHORIZATION, bearer));
	}

	/** Agendamento do estúdio dado, para o tutor dado, começando {@code hoursFromNow} horas a partir de agora (negativo = já passou). */
	private String appointment(UUID provider, UUID service, UUID who, long hoursFromNow, String statusName) {
		// Cada linha num dia diferente (o banco recusa dois agendamentos sobrepostos no mesmo estúdio): passado fica mais passado, futuro mais futuro.
		long days = ++seq * (hoursFromNow < 0 ? -1 : 1);
		OffsetDateTime start = OffsetDateTime.now().plusHours(hoursFromNow).plusDays(days).withSecond(0).withNano(0);
		return jdbc.queryForObject("INSERT INTO appointments (provider_id, service_id, customer_id, starts_at, ends_at, status, pet_id, pet_name, pet_species) "
				+ "VALUES (?, ?, ?, ?, ?, ?, ?, 'Pet de Teste', 'DOG') RETURNING id::text", String.class, provider, service, who, start, start.plusMinutes(30), statusName, petOf(who));
	}

	private String statusOf(String id) {
		return jdbc.queryForObject("SELECT status FROM appointments WHERE id = ?::uuid", String.class, id);
	}

	private List<String> events(String id) {
		return jdbc.queryForList("SELECT event_type FROM appointment_events WHERE appointment_id = ?::uuid ORDER BY created_at, id", String.class, id);
	}

	@Test
	void equipeRegistraFaltaDepoisDoHorarioEOEventoFicaNoHistorico() throws Exception {
		String id = appointment(providerA, serviceA, customer, -2, "SCHEDULED");
		noShow(tokenA, id).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("NO_SHOW"));
		assertEquals("NO_SHOW", statusOf(id));
		assertEquals(List.of("NO_SHOW"), events(id));
		String history = mvc.perform(get("/api/appointments/" + id + "/events").header(HttpHeaders.AUTHORIZATION, tokenA)).andExpect(status().isOk()).andReturn().getResponse()
				.getContentAsString();
		assertEquals("NO_SHOW", JsonPath.read(history, "$[0].type"));
		assertEquals("STAFF", JsonPath.read(history, "$[0].by"));
	}

	@Test
	void atendenteVinculadoTambemRegistra() throws Exception {
		String id = appointment(providerA, serviceA, customer, -1, "SCHEDULED");
		noShow(tokenAttendant, id).andExpect(status().isOk());
		assertEquals("NO_SHOW", statusOf(id));
	}

	@Test
	void antesDoHorarioEh422TooEarlyENadaMuda() throws Exception {
		String id = appointment(providerA, serviceA, customer, 2, "SCHEDULED");
		noShow(tokenA, id).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.reason").value("TOO_EARLY"));
		assertEquals("SCHEDULED", statusOf(id));
		assertEquals(List.of(), events(id));
	}

	@Test
	void soDeQuemEstavaAgendado_estadoFinalNaoVoltaNemRepete() throws Exception {
		for (String other : new String[] { "CHECKED_IN", "IN_PROGRESS", "READY", "COMPLETED" }) {
			String id = appointment(providerA, serviceA, customer, -3, other);
			noShow(tokenA, id).andExpect(status().isConflict()).andExpect(jsonPath("$.reason").value("INVALID_STATE"));
			assertEquals(other, statusOf(id));
		}
		String cancelled = appointment(providerA, serviceA, customer, -3, "CANCELLED");
		noShow(tokenA, cancelled).andExpect(status().isConflict()).andExpect(jsonPath("$.reason").value("ALREADY_CANCELLED"));
		String id = appointment(providerA, serviceA, customer, -3, "SCHEDULED");
		noShow(tokenA, id).andExpect(status().isOk());
		noShow(tokenA, id).andExpect(status().isConflict()).andExpect(jsonPath("$.reason").value("INVALID_STATE"));
		// Faltou é final: não dá para chegar, cancelar nem remarcar depois.
		mvc.perform(post("/api/appointments/" + id + "/check-in").header(HttpHeaders.AUTHORIZATION, tokenA)).andExpect(status().isConflict());
		mvc.perform(post("/api/appointments/" + id + "/cancel").header(HttpHeaders.AUTHORIZATION, tokenA)).andExpect(status().isConflict());
		assertEquals("NO_SHOW", statusOf(id));
	}

	@Test
	void tutorOutroEstudioEAdminNaoRegistram() throws Exception {
		String id = appointment(providerA, serviceA, customer, -2, "SCHEDULED");
		noShow(tokenCustomer, id).andExpect(status().isForbidden());
		noShow(tokenB, id).andExpect(status().isNotFound()); // outro estúdio: a mesma resposta de agendamento inexistente
		noShow(tokenB, UUID.randomUUID().toString()).andExpect(status().isNotFound());
		assertEquals("SCHEDULED", statusOf(id));
	}

	@Test
	void bancoSoAceitaOsEstadosEOsEventosConhecidos() {
		String id = appointment(providerA, serviceA, customer, -2, "SCHEDULED");
		assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("UPDATE appointments SET status = 'FALTOU' WHERE id = ?::uuid", id));
		jdbc.update("UPDATE appointments SET status = 'NO_SHOW' WHERE id = ?::uuid", id);
		assertEquals("NO_SHOW", statusOf(id));
	}

	@Test
	void agendaDoDiaMostraAsFaltasDoTutorSoNesteEstudio() throws Exception {
		// 2 faltas do tutor no estúdio A, 1 falta dele no estúdio B, 1 falta de outro tutor no A.
		appointment(providerA, serviceA, customer, -48, "NO_SHOW");
		appointment(providerA, serviceA, customer, -72, "NO_SHOW");
		appointment(providerB, serviceB, customer, -48, "NO_SHOW");
		appointment(providerA, serviceA, otherCustomer, -48, "NO_SHOW");
		// Agenda de hoje do estúdio A: um agendamento do tutor (agendado) e um do outro tutor.
		LocalDate today = LocalDate.now(SP);
		OffsetDateTime start = today.atTime(12, 0).atZone(SP).toOffsetDateTime();
		jdbc.update("INSERT INTO appointments (provider_id, service_id, customer_id, starts_at, ends_at, status, pet_id, pet_name, pet_species) VALUES (?, ?, ?, ?, ?, 'SCHEDULED', ?, 'Rex', 'DOG')",
				providerA, serviceA, customer, start, start.plusMinutes(30), petOf(customer));
		jdbc.update("INSERT INTO appointments (provider_id, service_id, customer_id, starts_at, ends_at, status, pet_id, pet_name, pet_species) VALUES (?, ?, ?, ?, ?, 'SCHEDULED', ?, 'Mel', 'DOG')",
				providerA, serviceA, otherCustomer, start.plusHours(1), start.plusHours(1).plusMinutes(30), petOf(otherCustomer));

		String body = mvc.perform(get("/api/agenda?date=" + today).header(HttpHeaders.AUTHORIZATION, tokenA)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		assertEquals(2, (int) JsonPath.read(body, "$[0].customerNoShows"), "o tutor faltou 2 vezes neste estúdio (a falta do estúdio B não conta)");
		assertEquals(1, (int) JsonPath.read(body, "$[1].customerNoShows"));
		// A semana, de várias linhas, não leva a contagem (como o telefone).
		String week = mvc.perform(get("/api/agenda/week?start=" + today).header(HttpHeaders.AUTHORIZATION, tokenA)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		assertEquals(0, (int) JsonPath.read(week, "$[0].customerNoShows"));
		// O estúdio B vê só a falta dele no próprio estúdio.
		OffsetDateTime startB = today.atTime(15, 0).atZone(SP).toOffsetDateTime();
		jdbc.update("INSERT INTO appointments (provider_id, service_id, customer_id, starts_at, ends_at, status, pet_id, pet_name, pet_species) VALUES (?, ?, ?, ?, ?, 'SCHEDULED', ?, 'Rex', 'DOG')",
				providerB, serviceB, customer, startB, startB.plusMinutes(30), petOf(customer));
		String bodyB = mvc.perform(get("/api/agenda?date=" + today).header(HttpHeaders.AUTHORIZATION, tokenB)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		assertEquals(1, (int) JsonPath.read(bodyB, "$[0].customerNoShows"));
	}

	@Test
	void tutorVeFaltouEmMeusAgendamentosEAgendamentoFaltosoNaoPodeSerCancelado() throws Exception {
		String id = appointment(providerA, serviceA, customer, -2, "SCHEDULED");
		noShow(tokenA, id).andExpect(status().isOk());
		mvc.perform(get("/api/appointments/mine").header(HttpHeaders.AUTHORIZATION, tokenCustomer)).andExpect(jsonPath("$[0].status").value("NO_SHOW"))
				.andExpect(jsonPath("$[0].freeChangeUntil").doesNotExist());
		mvc.perform(post("/api/appointments/" + id + "/cancel").header(HttpHeaders.AUTHORIZATION, tokenCustomer)).andExpect(status().isConflict());
	}
}
