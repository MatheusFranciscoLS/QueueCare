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
 * Estados de atendimento (ADR 0019, fatia 4): CHECKED_IN, IN_PROGRESS (em atendimento), READY (pronto para retirada) e COMPLETED (retirado).
 * Só para frente, só a equipe do prestador, com o evento de cada passo no histórico e a fila mostrando quem está em cada situação.
 */
class AttendanceStagesIntegrationTest extends AuthIntegrationTestBase {

	private static final ZoneId SP = ZoneId.of("America/Sao_Paulo");

	UUID ownerA, ownerB, attendantA, customer;
	UUID providerA, serviceA;
	String tokenA, tokenB, tokenAttendant, tokenCustomer;

	@BeforeEach
	void seed() throws Exception {
		ownerA = seedUser("ea@exemplo.test", "PROVIDER", true);
		ownerB = seedUser("eb@exemplo.test", "PROVIDER", true);
		attendantA = seedUser("eat@exemplo.test", "ATTENDANT", true);
		customer = seedUser("ec@exemplo.test", "CUSTOMER", true);
		providerA = jdbc.queryForObject("INSERT INTO providers (user_id, business_name, time_zone, minimum_notice_minutes, cancellation_deadline_minutes) "
				+ "VALUES (?, 'Negócio A', 'America/Sao_Paulo', 0, 120) RETURNING id", UUID.class, ownerA);
		jdbc.update("INSERT INTO providers (user_id, business_name, time_zone, minimum_notice_minutes, cancellation_deadline_minutes) VALUES (?, 'Negócio B', 'America/Sao_Paulo', 0, 120)", ownerB);
		serviceA = jdbc.queryForObject("INSERT INTO services (provider_id, name, duration_minutes) VALUES (?, 'Banho', 30) RETURNING id", UUID.class, providerA);
		tokenA = tokenOf("ea@exemplo.test");
		tokenB = tokenOf("eb@exemplo.test");
		tokenAttendant = tokenOf("eat@exemplo.test");
		tokenCustomer = tokenOf("ec@exemplo.test");
		send(tokenA, "/api/provider/staff", "{\"email\":\"eat@exemplo.test\"}").andExpect(status().isCreated());
	}

	private String tokenOf(String email) throws Exception {
		String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(json("email", email, "password", PASSWORD)))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		return "Bearer " + JsonPath.read(body, "$.accessToken");
	}

	private ResultActions send(String bearer, String path, String body) throws Exception {
		var builder = post(path).header(HttpHeaders.AUTHORIZATION, bearer);
		return mvc.perform(body == null ? builder : builder.contentType(MediaType.APPLICATION_JSON).content(body));
	}

	private ResultActions step(String bearer, String id, String action) throws Exception {
		return send(bearer, "/api/appointments/" + id + "/" + action, null);
	}

	/** Agendamento de HOJE às {@code hour}:00 (fuso de São Paulo), na situação dada. */
	private String appointment(int hour, String statusName) {
		OffsetDateTime start = LocalDate.now(SP).atTime(hour, 0).atZone(SP).toOffsetDateTime();
		return jdbc.queryForObject("INSERT INTO appointments (provider_id, service_id, customer_id, starts_at, ends_at, status, pet_id, pet_name, pet_species) "
				+ "VALUES (?, ?, ?, ?, ?, ?, ?, 'Pet de Teste', 'DOG') RETURNING id::text", String.class, providerA, serviceA, customer, start, start.plusMinutes(30), statusName, petOf(customer));
	}

	private String statusOf(String id) {
		return jdbc.queryForObject("SELECT status FROM appointments WHERE id = ?::uuid", String.class, id);
	}

	private List<String> events(String id) {
		return jdbc.queryForList("SELECT event_type FROM appointment_events WHERE appointment_id = ?::uuid ORDER BY created_at, id", String.class, id);
	}

	@Test
	void sequenciaCompletaChegouEmAtendimentoProntoRetirado() throws Exception {
		String id = appointment(14, "SCHEDULED");
		step(tokenA, id, "check-in").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CHECKED_IN"));
		step(tokenA, id, "start").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("IN_PROGRESS"));
		step(tokenAttendant, id, "ready").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("READY"));
		step(tokenA, id, "complete").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("COMPLETED"));
		assertEquals(List.of("CHECKED_IN", "STARTED", "READY", "COMPLETED"), events(id));
	}

	@Test
	void comecarEhOpcional_prontoEConclusaoAceitamSaltosParaFrente() throws Exception {
		String ready = appointment(14, "CHECKED_IN");
		step(tokenA, ready, "ready").andExpect(status().isOk());
		assertEquals("READY", statusOf(ready));
		String direct = appointment(15, "CHECKED_IN");
		step(tokenA, direct, "complete").andExpect(status().isOk()); // o fluxo antigo continua valendo
		assertEquals("COMPLETED", statusOf(direct));
		String started = appointment(16, "IN_PROGRESS");
		step(tokenA, started, "complete").andExpect(status().isOk());
		assertEquals("COMPLETED", statusOf(started));
	}

	@Test
	void nuncaParaTras() throws Exception {
		String inProgress = appointment(14, "IN_PROGRESS");
		step(tokenA, inProgress, "start").andExpect(status().isConflict()).andExpect(jsonPath("$.reason").value("INVALID_STATE"));
		step(tokenA, inProgress, "check-in").andExpect(status().isConflict());
		String ready = appointment(15, "READY");
		step(tokenA, ready, "start").andExpect(status().isConflict());
		step(tokenA, ready, "ready").andExpect(status().isConflict());
		step(tokenA, ready, "cancel").andExpect(status().isConflict()); // quem já chegou não se cancela
		String done = appointment(16, "COMPLETED");
		for (String action : new String[] { "start", "ready", "complete" }) {
			step(tokenA, done, action).andExpect(status().isConflict());
		}
		assertEquals("READY", statusOf(ready));
		assertEquals("COMPLETED", statusOf(done));
	}

	@Test
	void quemNaoChegouNaoComecaNemFicaProntoNemConclui() throws Exception {
		String scheduled = appointment(14, "SCHEDULED");
		for (String action : new String[] { "start", "ready", "complete" }) {
			step(tokenA, scheduled, action).andExpect(status().isConflict()).andExpect(jsonPath("$.reason").value("INVALID_STATE"));
		}
		String cancelled = appointment(15, "CANCELLED");
		for (String action : new String[] { "start", "ready", "complete" }) {
			step(tokenA, cancelled, action).andExpect(status().isConflict()).andExpect(jsonPath("$.reason").value("ALREADY_CANCELLED"));
		}
		assertEquals("SCHEDULED", statusOf(scheduled));
	}

	@Test
	void soAEquipeDoPrestadorOpera() throws Exception {
		String id = appointment(14, "CHECKED_IN");
		for (String action : new String[] { "start", "ready" }) {
			step(tokenCustomer, id, action).andExpect(status().isForbidden());
			step(tokenB, id, action).andExpect(status().isNotFound()); // outro prestador: igual a inexistente
			step("Bearer invalido", id, action).andExpect(status().isUnauthorized());
		}
		assertEquals("CHECKED_IN", statusOf(id));
		assertEquals(0, count("SELECT count(*) FROM appointment_events WHERE appointment_id = ?::uuid AND event_type IN ('STARTED', 'READY')", id));
	}

	@Test
	void filaMostraSituacaoDeCadaUmNaOrdemDeChegadaEConcluidoSai() throws Exception {
		String first = appointment(14, "SCHEDULED");
		String second = appointment(15, "SCHEDULED");
		String third = appointment(16, "SCHEDULED");
		for (String id : new String[] { first, second, third }) {
			step(tokenA, id, "check-in").andExpect(status().isOk());
		}
		step(tokenA, first, "start").andExpect(status().isOk());
		step(tokenA, second, "ready").andExpect(status().isOk());
		String body = mvc.perform(get("/api/agenda/queue").header(HttpHeaders.AUTHORIZATION, tokenA)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		assertEquals(List.of(first, second, third), JsonPath.read(body, "$[*].id"));
		assertEquals(List.of("IN_PROGRESS", "READY", "CHECKED_IN"), JsonPath.read(body, "$[*].status"));
		step(tokenA, second, "complete").andExpect(status().isOk());
		String after = mvc.perform(get("/api/agenda/queue").header(HttpHeaders.AUTHORIZATION, tokenA)).andReturn().getResponse().getContentAsString();
		assertEquals(List.of(first, third), JsonPath.read(after, "$[*].id"));
	}

	@Test
	void historicoMostraOsNovosPassosParaOClienteEParaAEquipe() throws Exception {
		String id = appointment(14, "SCHEDULED");
		jdbc.update("INSERT INTO appointment_events (appointment_id, actor_id, event_type) VALUES (?::uuid, ?, 'SCHEDULED')", id, customer);
		step(tokenA, id, "check-in").andExpect(status().isOk());
		step(tokenA, id, "start").andExpect(status().isOk());
		step(tokenA, id, "ready").andExpect(status().isOk());
		for (String bearer : new String[] { tokenCustomer, tokenA }) {
			String body = mvc.perform(get("/api/appointments/" + id + "/events").header(HttpHeaders.AUTHORIZATION, bearer)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
			assertEquals(List.of("SCHEDULED", "CHECKED_IN", "STARTED", "READY"), JsonPath.read(body, "$[*].type"));
		}
	}

	@Test
	void clienteNaoVeQuemMexeNoPasso() throws Exception {
		String id = appointment(14, "CHECKED_IN");
		step(tokenA, id, "start").andExpect(status().isOk());
		String body = mvc.perform(get("/api/appointments/" + id + "/events").header(HttpHeaders.AUTHORIZATION, tokenCustomer)).andReturn().getResponse().getContentAsString();
		org.junit.jupiter.api.Assertions.assertNull(JsonPath.read(body, "$[0].actorName"), "o cliente nunca vê o nome de quem agiu");
	}

	@Test
	void bancoGarantiaAsSituacoesEOsEventos() throws Exception {
		String id = appointment(14, "CHECKED_IN");
		assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("UPDATE appointments SET status = 'PRONTO' WHERE id = ?::uuid", id));
		assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("INSERT INTO appointment_events (appointment_id, event_type) VALUES (?::uuid, 'PRONTO')", id));
		jdbc.update("INSERT INTO appointment_events (appointment_id, event_type) VALUES (?::uuid, 'READY')", id);
		assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("INSERT INTO appointment_events (appointment_id, event_type) VALUES (?::uuid, 'READY')", id),
				"um evento de cada tipo por agendamento");
	}

	@Test
	void dois_cliquesAoMesmoTempoUmVenceEOutroRecebe409() throws Exception {
		String id = appointment(14, "CHECKED_IN");
		java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
		java.util.concurrent.CyclicBarrier barrier = new java.util.concurrent.CyclicBarrier(2);
		List<java.util.concurrent.Future<Integer>> results = new java.util.ArrayList<>();
		for (int i = 0; i < 2; i++) {
			results.add(pool.submit(() -> {
				barrier.await();
				return step(tokenA, id, "start").andReturn().getResponse().getStatus();
			}));
		}
		List<Integer> codes = new java.util.ArrayList<>();
		for (var f : results) {
			codes.add(f.get(30, java.util.concurrent.TimeUnit.SECONDS));
		}
		pool.shutdown();
		assertEquals(List.of(200, 409), codes.stream().sorted().toList());
		assertEquals(1, count("SELECT count(*) FROM appointment_events WHERE appointment_id = ?::uuid AND event_type = 'STARTED'", id));
	}
}
