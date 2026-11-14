package com.queuecare.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/**
 * R6: chegada (check-in), conclusão e fila do dia, com tokens reais, filtros reais e PostgreSQL real. Dois prestadores (A e B),
 * um atendente vinculado a cada um, um atendente solto e dois clientes. Os agendamentos são criados direto no banco em horários
 * FIXOS do dia (meio-dia no fuso do prestador em diante), para o teste não depender da hora em que roda.
 */
class AttendanceApiIntegrationTest extends AuthIntegrationTestBase {

	private static final ZoneId SP = ZoneId.of("America/Sao_Paulo");

	UUID userPA, userPB, userA1, userB1, userC1, userC2;
	UUID providerA, providerB, serviceA, serviceB;
	String pa, pb, a1, b1, s, c1, c2;

	@BeforeEach
	void seedMatrix() throws Exception {
		userPA = seedUser("pa@exemplo.test", "PROVIDER", true);
		userPB = seedUser("pb@exemplo.test", "PROVIDER", true);
		userA1 = seedUser("a1@exemplo.test", "ATTENDANT", true);
		userB1 = seedUser("b1@exemplo.test", "ATTENDANT", true);
		seedUser("solto@exemplo.test", "ATTENDANT", true);
		userC1 = seedUser("c1@exemplo.test", "CUSTOMER", true);
		userC2 = seedUser("c2@exemplo.test", "CUSTOMER", true);
		providerA = business(userPA, "Negócio A");
		providerB = business(userPB, "Negócio B");
		serviceA = service(providerA);
		serviceB = service(providerB);
		pa = tokenOf("pa@exemplo.test");
		pb = tokenOf("pb@exemplo.test");
		a1 = tokenOf("a1@exemplo.test");
		b1 = tokenOf("b1@exemplo.test");
		s = tokenOf("solto@exemplo.test");
		c1 = tokenOf("c1@exemplo.test");
		c2 = tokenOf("c2@exemplo.test");
		send(pa, "/api/provider/staff", "{\"email\":\"a1@exemplo.test\"}").andExpect(status().isCreated());
		send(pb, "/api/provider/staff", "{\"email\":\"b1@exemplo.test\"}").andExpect(status().isCreated());
	}

	// ---------- helpers ----------

	private UUID business(UUID owner, String name) {
		return jdbc.queryForObject("INSERT INTO providers (user_id, business_name, time_zone, minimum_notice_minutes, cancellation_deadline_minutes) "
				+ "VALUES (?, ?, 'America/Sao_Paulo', 0, 120) RETURNING id", UUID.class, owner, name);
	}

	private UUID service(UUID provider) {
		return jdbc.queryForObject("INSERT INTO services (provider_id, name, duration_minutes) VALUES (?, 'Serviço', 30) RETURNING id", UUID.class, provider);
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

	/** Agendamento em SCHEDULED às {@code hour}:00 (fuso de São Paulo) do dia {@code dayOffset} em relação a hoje. */
	private String appointment(UUID provider, UUID service, UUID customer, int dayOffset, int hour, String status) {
		OffsetDateTime start = LocalDate.now(SP).plusDays(dayOffset).atTime(hour, 0).atZone(SP).toOffsetDateTime();
		return jdbc.queryForObject("INSERT INTO appointments (provider_id, service_id, customer_id, starts_at, ends_at, status) VALUES (?, ?, ?, ?, ?, ?) RETURNING id::text",
				String.class, provider, service, customer, start, start.plusMinutes(30), status);
	}

	private ResultActions checkIn(String bearer, String id) throws Exception {
		return send(bearer, "/api/appointments/" + id + "/check-in", null);
	}

	private ResultActions complete(String bearer, String id) throws Exception {
		return send(bearer, "/api/appointments/" + id + "/complete", null);
	}

	private List<String> queueIds(String bearer) throws Exception {
		String body = mvc.perform(get("/api/agenda/queue").header(HttpHeaders.AUTHORIZATION, bearer)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		return JsonPath.read(body, "$[*].id");
	}

	private String statusOf(String id) {
		return jdbc.queryForObject("SELECT status FROM appointments WHERE id = ?::uuid", String.class, id);
	}

	private long events(String id, String type) {
		return jdbc.queryForObject("SELECT count(*) FROM appointment_events WHERE appointment_id = ?::uuid AND event_type = ?", Long.class, id, type);
	}

	// ---------- fluxo completo ----------

	@Test
	void chegadaEConclusaoGravamEventoComQuemFezEMudamAFila() throws Exception {
		String id = appointment(providerA, serviceA, userC1, 0, 12, "SCHEDULED");

		checkIn(a1, id).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CHECKED_IN")).andExpect(jsonPath("$.businessName").value("Negócio A"));
		assertEquals("CHECKED_IN", statusOf(id));
		assertEquals(userA1, jdbc.queryForObject("SELECT actor_id FROM appointment_events WHERE appointment_id = ?::uuid AND event_type = 'CHECKED_IN'", UUID.class, id));
		assertEquals(List.of(id), queueIds(pa), "o dono vê quem o atendente registrou");

		complete(pa, id).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("COMPLETED"));
		assertEquals("COMPLETED", statusOf(id));
		assertEquals(userPA, jdbc.queryForObject("SELECT actor_id FROM appointment_events WHERE appointment_id = ?::uuid AND event_type = 'COMPLETED'", UUID.class, id));
		assertEquals(List.of(), queueIds(a1), "concluído sai da fila");
	}

	// ---------- isolamento e perfis ----------

	@Test
	void matrizHostilNinguemOperaOAgendamentoDeOutroPrestadorNemSendoCliente() throws Exception {
		String idA = appointment(providerA, serviceA, userC1, 0, 12, "SCHEDULED");
		String idB = appointment(providerB, serviceB, userC2, 0, 12, "SCHEDULED");

		checkIn(pb, idA).andExpect(status().isNotFound());
		checkIn(b1, idA).andExpect(status().isNotFound());
		checkIn(pa, idB).andExpect(status().isNotFound());
		checkIn(a1, idB).andExpect(status().isNotFound());
		checkIn(a1, UUID.randomUUID().toString()).andExpect(status().isNotFound());
		for (String forbidden : List.of(c1, c2, s)) { // cliente e atendente sem vínculo
			checkIn(forbidden, idA).andExpect(status().isForbidden());
			checkIn(forbidden, idB).andExpect(status().isForbidden());
			complete(forbidden, idA).andExpect(status().isForbidden());
		}
		mvc.perform(post("/api/appointments/" + idA + "/check-in")).andExpect(status().isUnauthorized());
		mvc.perform(post("/api/appointments/" + idA + "/complete").header(HttpHeaders.AUTHORIZATION, "Bearer invalido")).andExpect(status().isUnauthorized());

		assertEquals("SCHEDULED", statusOf(idA), "nada mudou depois de todas as tentativas");
		assertEquals("SCHEDULED", statusOf(idB));
		assertEquals(0L, count("SELECT count(*) FROM appointment_events WHERE event_type IN ('CHECKED_IN', 'COMPLETED')"));

		// o mesmo para concluir um agendamento já em atendimento
		checkIn(pa, idA).andExpect(status().isOk());
		complete(pb, idA).andExpect(status().isNotFound());
		complete(b1, idA).andExpect(status().isNotFound());
		assertEquals("CHECKED_IN", statusOf(idA));
	}

	@Test
	void atendenteDesvinculadoOuDonoDesativadoPerdeOAcessoNaHora() throws Exception {
		String id = appointment(providerA, serviceA, userC1, 0, 12, "SCHEDULED");
		mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/provider/staff/" + userA1).header(HttpHeaders.AUTHORIZATION, pa)).andExpect(status().isNoContent());
		checkIn(a1, id).andExpect(status().isForbidden());

		send(pa, "/api/provider/staff", "{\"email\":\"a1@exemplo.test\"}").andExpect(status().isCreated());
		jdbc.update("UPDATE users SET active = false WHERE id = ?", userPA);
		checkIn(a1, id).andExpect(status().isForbidden()); // atendente só opera se o dono estiver ativo
		assertEquals("SCHEDULED", statusOf(id));
	}

	// ---------- máquina de estados ----------

	@Test
	void soSeAvancaNaOrdemAgendadoChegouConcluidoEOutrasCombinacoesSaoConflito() throws Exception {
		String id = appointment(providerA, serviceA, userC1, 0, 12, "SCHEDULED");

		complete(pa, id).andExpect(status().isConflict()).andExpect(jsonPath("$.reason").value("INVALID_STATE")); // não chegou ainda
		checkIn(pa, id).andExpect(status().isOk());
		checkIn(pa, id).andExpect(status().isConflict()).andExpect(jsonPath("$.reason").value("INVALID_STATE")); // já chegou
		complete(pa, id).andExpect(status().isOk());
		complete(pa, id).andExpect(status().isConflict()).andExpect(jsonPath("$.reason").value("INVALID_STATE"));
		checkIn(pa, id).andExpect(status().isConflict()).andExpect(jsonPath("$.reason").value("INVALID_STATE")); // não volta

		assertEquals("COMPLETED", statusOf(id));
		assertEquals(1L, events(id, "CHECKED_IN"));
		assertEquals(1L, events(id, "COMPLETED"));
	}

	@Test
	void canceladoNaoChegaNemConcluiEAgendamentoEmAtendimentoNaoSeCancela() throws Exception {
		String cancelled = appointment(providerA, serviceA, userC1, 0, 12, "CANCELLED");
		checkIn(pa, cancelled).andExpect(status().isConflict()).andExpect(jsonPath("$.reason").value("ALREADY_CANCELLED"));
		complete(pa, cancelled).andExpect(status().isConflict()).andExpect(jsonPath("$.reason").value("ALREADY_CANCELLED"));
		assertEquals(0L, events(cancelled, "CHECKED_IN"));

		String inService = appointment(providerA, serviceA, userC1, 0, 14, "SCHEDULED");
		checkIn(pa, inService).andExpect(status().isOk());
		send(pa, "/api/appointments/" + inService + "/cancel", null).andExpect(status().isConflict()).andExpect(jsonPath("$.reason").value("NOT_CANCELLABLE"));
		assertEquals("CHECKED_IN", statusOf(inService));
	}

	@Test
	void checkInSoVaiNoDiaDoAgendamentoNoFusoDoPrestador() throws Exception {
		String tomorrow = appointment(providerA, serviceA, userC1, 1, 12, "SCHEDULED");
		String yesterday = appointment(providerA, serviceA, userC2, -1, 12, "SCHEDULED");
		String today = appointment(providerA, serviceA, userC2, 0, 12, "SCHEDULED");

		checkIn(pa, tomorrow).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.reason").value("WRONG_DAY"));
		checkIn(a1, yesterday).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.reason").value("WRONG_DAY"));
		assertEquals("SCHEDULED", statusOf(tomorrow));
		assertEquals("SCHEDULED", statusOf(yesterday));
		assertEquals(0L, events(tomorrow, "CHECKED_IN"));
		checkIn(a1, today).andExpect(status().isOk());
	}

	// ---------- fila ----------

	@Test
	void filaVemPorOrdemDeChegadaNaoPorHorarioNemPorId() throws Exception {
		String early = appointment(providerA, serviceA, userC1, 0, 12, "SCHEDULED");
		String middle = appointment(providerA, serviceA, userC2, 0, 13, "SCHEDULED");
		String late = appointment(providerA, serviceA, userC1, 0, 14, "SCHEDULED");
		// chega primeiro quem tem o horário MAIS TARDE
		checkIn(pa, late).andExpect(status().isOk());
		checkIn(a1, early).andExpect(status().isOk());
		checkIn(pa, middle).andExpect(status().isOk());
		// instantes de chegada controlados: late (08:00Z), early (09:00Z), middle (10:00Z) do dia
		setArrival(late, 8);
		setArrival(early, 9);
		setArrival(middle, 10);

		String body = mvc.perform(get("/api/agenda/queue").header(HttpHeaders.AUTHORIZATION, a1)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		assertEquals(List.of(late, early, middle), JsonPath.<List<String>>read(body, "$[*].id"));
		assertEquals(List.of(1, 2, 3), JsonPath.<List<Integer>>read(body, "$[*].position"));
		assertEquals(List.of(late, early, middle), queueIds(pa), "o dono vê a mesma fila");
	}

	private void setArrival(String id, int hourUtc) {
		jdbc.update("UPDATE appointment_events SET created_at = date_trunc('day', now()) + make_interval(hours => ?) WHERE appointment_id = ?::uuid AND event_type = 'CHECKED_IN'", hourUtc, id);
	}

	@Test
	void filaTemSoQuemChegouHojeDoPrestadorDeQuemPergunta() throws Exception {
		String waiting = appointment(providerA, serviceA, userC1, 0, 12, "SCHEDULED");
		String notYet = appointment(providerA, serviceA, userC2, 0, 14, "SCHEDULED");
		String done = appointment(providerA, serviceA, userC1, 0, 16, "SCHEDULED");
		String other = appointment(providerB, serviceB, userC2, 0, 12, "SCHEDULED");
		String yesterdayStuck = appointment(providerA, serviceA, userC1, -1, 12, "CHECKED_IN"); // esquecido de ontem
		jdbc.update("INSERT INTO appointment_events (appointment_id, actor_id, event_type) VALUES (?::uuid, ?, 'CHECKED_IN')", yesterdayStuck, userA1);
		checkIn(pa, waiting).andExpect(status().isOk());
		checkIn(pa, done).andExpect(status().isOk());
		complete(pa, done).andExpect(status().isOk());
		checkIn(pb, other).andExpect(status().isOk());

		assertEquals(List.of(waiting), queueIds(a1), "sem o agendado, o concluído, o de ontem e o do outro prestador");
		assertEquals(List.of(other), queueIds(b1));
		assertTrue(!queueIds(pa).contains(notYet));
		// "providerId" na URL é ignorado: nunca troca o contexto
		String body = mvc.perform(get("/api/agenda/queue").param("providerId", providerB.toString()).header(HttpHeaders.AUTHORIZATION, a1)).andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
		assertEquals(List.of(waiting), JsonPath.<List<String>>read(body, "$[*].id"));
	}

	@Test
	void filaMostraNomeDoClienteSemOutrosDadosPessoaisESoParaAEquipe() throws Exception {
		String id = appointment(providerA, serviceA, userC1, 0, 12, "SCHEDULED");
		checkIn(pa, id).andExpect(status().isOk());

		mvc.perform(get("/api/agenda/queue").header(HttpHeaders.AUTHORIZATION, a1)).andExpect(status().isOk())
				.andExpect(jsonPath("$[0].customerName").value("Fulano de Teste")).andExpect(jsonPath("$[0].serviceName").value("Serviço"))
				.andExpect(jsonPath("$[0].position").value(1)).andExpect(jsonPath("$[0].checkedInAt").exists())
				.andExpect(jsonPath("$[0].customerEmail").doesNotExist()).andExpect(jsonPath("$[0].email").doesNotExist());
		for (String token : List.of(c1, c2, s)) {
			mvc.perform(get("/api/agenda/queue").header(HttpHeaders.AUTHORIZATION, token)).andExpect(status().isForbidden());
		}
		mvc.perform(get("/api/agenda/queue")).andExpect(status().isUnauthorized());
		seedUser("sem-negocio@exemplo.test", "PROVIDER", true);
		mvc.perform(get("/api/agenda/queue").header(HttpHeaders.AUTHORIZATION, tokenOf("sem-negocio@exemplo.test"))).andExpect(status().isNotFound())
				.andExpect(jsonPath("$.reason").value("PROVIDER_NOT_CONFIGURED"));
	}

	// ---------- concorrência ----------

	@Test
	void doisAtendentesRegistrandoAMesmaChegadaUmVenceEOutroRecebeConflito() throws Exception {
		String id = appointment(providerA, serviceA, userC1, 0, 12, "SCHEDULED");
		for (int round = 0; round < 3; round++) {
			jdbc.update("UPDATE appointments SET status = 'SCHEDULED', version = version + 1 WHERE id = ?::uuid", id);
			jdbc.update("DELETE FROM appointment_events WHERE appointment_id = ?::uuid", id);
			CyclicBarrier barrier = new CyclicBarrier(2);
			List<Callable<Integer>> tasks = new ArrayList<>();
			for (String token : List.of(pa, a1)) {
				tasks.add(() -> {
					barrier.await(30, TimeUnit.SECONDS);
					return checkIn(token, id).andReturn().getResponse().getStatus();
				});
			}
			ExecutorService pool = Executors.newFixedThreadPool(2);
			try {
				List<Integer> statuses = new ArrayList<>();
				for (Future<Integer> f : pool.invokeAll(tasks, 60, TimeUnit.SECONDS)) {
					statuses.add(f.get());
				}
				assertEquals(1, statuses.stream().filter(code -> code == 200).count(), "exatamente um vence: " + statuses);
				assertEquals(1, statuses.stream().filter(code -> code == 409).count(), "o outro recebe conflito: " + statuses);
			} finally {
				pool.shutdownNow();
			}
			assertEquals("CHECKED_IN", statusOf(id));
			assertEquals(1L, events(id, "CHECKED_IN"), "um único evento de chegada");
		}
	}
}
