package com.queuecare.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Desfazer a última etapa (ADR 0026): quem tocou sem querer em Chegou, Começar ou Pronto volta UMA etapa. Só de atendimento em andamento, só a equipe do
 * estúdio; o histórico guarda a correção; a etapa pode ser refeita; concluído, faltou e cancelado são finais.
 */
class UndoStepIntegrationTest extends AuthIntegrationTestBase {

	private static final ZoneId SP = ZoneId.of("America/Sao_Paulo");

	UUID ownerA, ownerB, attendantA, customer;
	UUID providerA, serviceA;
	String tokenA, tokenB, tokenAttendant, tokenCustomer;

	@BeforeEach
	void seed() throws Exception {
		ownerA = seedUser("ua@exemplo.test", "PROVIDER", true);
		ownerB = seedUser("ub@exemplo.test", "PROVIDER", true);
		attendantA = seedUser("uat@exemplo.test", "ATTENDANT", true);
		customer = seedUser("uc@exemplo.test", "CUSTOMER", true);
		providerA = jdbc.queryForObject("INSERT INTO providers (user_id, business_name, time_zone, minimum_notice_minutes, cancellation_deadline_minutes) "
				+ "VALUES (?, 'Negócio A', 'America/Sao_Paulo', 0, 120) RETURNING id", UUID.class, ownerA);
		jdbc.update("INSERT INTO providers (user_id, business_name, time_zone, minimum_notice_minutes, cancellation_deadline_minutes) VALUES (?, 'Negócio B', 'America/Sao_Paulo', 0, 120)", ownerB);
		serviceA = jdbc.queryForObject("INSERT INTO services (provider_id, name, duration_minutes) VALUES (?, 'Banho', 30) RETURNING id", UUID.class, providerA);
		tokenA = tokenOf("ua@exemplo.test");
		tokenB = tokenOf("ub@exemplo.test");
		tokenAttendant = tokenOf("uat@exemplo.test");
		tokenCustomer = tokenOf("uc@exemplo.test");
		mvc.perform(post("/api/provider/staff").header(HttpHeaders.AUTHORIZATION, tokenA).contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"uat@exemplo.test\"}"))
				.andExpect(status().isCreated());
	}

	private String tokenOf(String email) throws Exception {
		String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(json("email", email, "password", PASSWORD)))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		return "Bearer " + JsonPath.read(body, "$.accessToken");
	}

	private ResultActions step(String bearer, String id, String action) throws Exception {
		return mvc.perform(post("/api/appointments/" + id + "/" + action).header(HttpHeaders.AUTHORIZATION, bearer));
	}

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
	void desfazerAChegadaVoltaParaAgendadoERegistraACorrecao() throws Exception {
		String id = appointment(14, "SCHEDULED");
		step(tokenAttendant, id, "check-in").andExpect(status().isOk());
		step(tokenAttendant, id, "undo").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SCHEDULED"));
		assertEquals("SCHEDULED", statusOf(id));
		assertEquals(List.of("UNDONE"), events(id));
		assertEquals("CHECKED_IN", jdbc.queryForObject("SELECT notes FROM appointment_events WHERE appointment_id = ?::uuid AND event_type = 'UNDONE'", String.class, id));
	}

	@Test
	void cadaEtapaVoltaUmaSoEAEtapaPodeSerRefeita() throws Exception {
		String id = appointment(14, "SCHEDULED");
		step(tokenA, id, "check-in").andExpect(status().isOk());
		step(tokenA, id, "start").andExpect(status().isOk());
		step(tokenA, id, "ready").andExpect(status().isOk());

		step(tokenA, id, "undo").andExpect(jsonPath("$.status").value("IN_PROGRESS")); // pronto -> em atendimento
		step(tokenA, id, "undo").andExpect(jsonPath("$.status").value("CHECKED_IN")); // em atendimento -> chegou
		step(tokenA, id, "undo").andExpect(jsonPath("$.status").value("SCHEDULED")); // chegou -> agendado
		assertEquals(List.of("UNDONE", "UNDONE", "UNDONE"), events(id));

		// Refazer funciona (o índice de um evento por tipo não bloqueia): e a fila vê a NOVA chegada.
		step(tokenA, id, "check-in").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CHECKED_IN"));
		assertEquals(List.of("UNDONE", "UNDONE", "UNDONE", "CHECKED_IN"), events(id));
	}

	@Test
	void prontoSemTerComecadoVoltaParaChegou() throws Exception {
		String id = appointment(14, "SCHEDULED");
		step(tokenA, id, "check-in").andExpect(status().isOk());
		step(tokenA, id, "ready").andExpect(status().isOk()); // pulou "começar"
		step(tokenA, id, "undo").andExpect(jsonPath("$.status").value("CHECKED_IN"));
	}

	@Test
	void quemVoltaERefazChegaNoFimDaFila() throws Exception {
		String first = appointment(10, "SCHEDULED");
		String second = appointment(11, "SCHEDULED");
		step(tokenA, first, "check-in").andExpect(status().isOk());
		step(tokenA, second, "check-in").andExpect(status().isOk());
		step(tokenA, first, "undo").andExpect(status().isOk());
		step(tokenA, first, "check-in").andExpect(status().isOk());

		String queue = mvc.perform(get("/api/agenda/queue").header(HttpHeaders.AUTHORIZATION, tokenA)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		assertEquals(List.of(second, first), JsonPath.read(queue, "$[*].id"), "quem chegou primeiro agora é o segundo; o que refez a chegada vai para o fim");
	}

	@Test
	void situacoesFinaisEAgendadoNaoSaoDesfeitas() throws Exception {
		for (String finalStatus : List.of("COMPLETED", "NO_SHOW")) {
			step(tokenA, appointment(finalStatus.equals("COMPLETED") ? 8 : 9, finalStatus), "undo").andExpect(status().isConflict());
		}
		step(tokenA, appointment(12, "SCHEDULED"), "undo").andExpect(status().isConflict()); // não há o que desfazer
		step(tokenA, appointment(13, "CANCELLED"), "undo").andExpect(status().isConflict());
	}

	@Test
	void soAEquipeDoProprioEstudioDesfaz() throws Exception {
		String id = appointment(14, "SCHEDULED");
		step(tokenA, id, "check-in").andExpect(status().isOk());
		step(tokenB, id, "undo").andExpect(status().isNotFound()); // outro estúdio: como se não existisse
		step(tokenCustomer, id, "undo").andExpect(status().isForbidden());
		mvc.perform(post("/api/appointments/" + id + "/undo")).andExpect(status().isUnauthorized());
		assertEquals("CHECKED_IN", statusOf(id), "ninguém de fora mexeu");
		step(tokenB, UUID.randomUUID().toString(), "undo").andExpect(status().isNotFound());
	}

	@Test
	void ohistoricoMostraACorrecaoParaAEquipeESemNomeParaOTutor() throws Exception {
		String id = appointment(14, "SCHEDULED");
		step(tokenA, id, "check-in").andExpect(status().isOk());
		step(tokenA, id, "undo").andExpect(status().isOk());

		mvc.perform(get("/api/appointments/" + id + "/events").header(HttpHeaders.AUTHORIZATION, tokenA)).andExpect(status().isOk()).andExpect(jsonPath("$[0].type").value("UNDONE"))
				.andExpect(jsonPath("$[0].undone").value("CHECKED_IN")).andExpect(jsonPath("$[0].by").value("STAFF")).andExpect(jsonPath("$[0].actorName").value("Fulano de Teste"));
		mvc.perform(get("/api/appointments/" + id + "/events").header(HttpHeaders.AUTHORIZATION, tokenCustomer)).andExpect(status().isOk()).andExpect(jsonPath("$[0].type").value("UNDONE"))
				.andExpect(jsonPath("$[0].actorName").doesNotExist());
	}
}
