package com.queuecare.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Folgas, feriados e férias (ADR 0024): o estúdio fecha por dias inteiros; a reserva, a remarcação, a consulta de horários e a lista de espera
 * respeitam o fechamento; fechar nunca cancela agendamentos sozinho (a resposta só conta quantos já existiam no período); cada estúdio só mexe nos seus.
 */
class TimeOffIntegrationTest extends AuthIntegrationTestBase {

	private static final ZoneId SP = ZoneId.of("America/Sao_Paulo");

	UUID ownerA, ownerB, tutor;
	UUID providerA, serviceA, petOfTutor;
	String tokenA, tokenB, tokenTutor;

	@BeforeEach
	void seed() throws Exception {
		ownerA = seedUser("fa@exemplo.test", "PROVIDER", true);
		ownerB = seedUser("fb@exemplo.test", "PROVIDER", true);
		tutor = seedUser("ft@exemplo.test", "CUSTOMER", true);
		providerA = jdbc.queryForObject("INSERT INTO providers (user_id, business_name, time_zone, minimum_notice_minutes, cancellation_deadline_minutes) "
				+ "VALUES (?, 'Negócio A', 'America/Sao_Paulo', 0, 120) RETURNING id", UUID.class, ownerA);
		jdbc.update("INSERT INTO providers (user_id, business_name, time_zone, minimum_notice_minutes, cancellation_deadline_minutes) VALUES (?, 'Negócio B', 'America/Sao_Paulo', 0, 120)",
				ownerB);
		jdbc.update("INSERT INTO availability_windows (provider_id, day_of_week, start_time, end_time) SELECT ?, d, '08:00', '18:00' FROM generate_series(1, 7) d", providerA);
		serviceA = jdbc.queryForObject("INSERT INTO services (provider_id, name, duration_minutes) VALUES (?, 'Banho', 60) RETURNING id", UUID.class, providerA);
		petOfTutor = jdbc.queryForObject("INSERT INTO pets (customer_id, name, species) VALUES (?, 'Rex', 'DOG') RETURNING id", UUID.class, tutor);
		tokenA = tokenOf("fa@exemplo.test");
		tokenB = tokenOf("fb@exemplo.test");
		tokenTutor = tokenOf("ft@exemplo.test");
	}

	private String tokenOf(String email) throws Exception {
		String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(json("email", email, "password", PASSWORD)))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		return "Bearer " + JsonPath.read(body, "$.accessToken");
	}

	private static LocalDate day(int ahead) {
		return LocalDate.now(SP).plusDays(ahead);
	}

	private ResultActions close(String bearer, LocalDate from, LocalDate to, String reason) throws Exception {
		String body = "{\"startsOn\":\"" + from + "\",\"endsOn\":\"" + to + "\"" + (reason == null ? "" : ",\"reason\":\"" + reason + "\"") + "}";
		return mvc.perform(post("/api/provider/time-off").header(HttpHeaders.AUTHORIZATION, bearer).contentType(MediaType.APPLICATION_JSON).content(body));
	}

	private ResultActions book(int ahead) throws Exception {
		String start = day(ahead) + "T10:00:00";
		return mvc.perform(post("/api/appointments").header(HttpHeaders.AUTHORIZATION, tokenTutor).contentType(MediaType.APPLICATION_JSON)
				.content("{\"providerId\":\"" + providerA + "\",\"serviceId\":\"" + serviceA + "\",\"petId\":\"" + petOfTutor + "\",\"localStart\":\"" + start + "\"}"));
	}

	private List<Object> slots(int ahead) throws Exception {
		String body = mvc.perform(get("/api/providers/" + providerA + "/slots").param("serviceId", serviceA.toString()).param("date", day(ahead).toString())
				.header(HttpHeaders.AUTHORIZATION, tokenTutor)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		return JsonPath.read(body, "$");
	}

	@Test
	void diaFechadoNaoOfereceHorarioNemAceitaReservaERemoverReabre() throws Exception {
		assertFalse(slots(5).isEmpty(), "antes de fechar há horários");
		String created = close(tokenA, day(5), day(6), "Feriado").andExpect(status().isCreated()).andExpect(jsonPath("$.reason").value("Feriado"))
				.andExpect(jsonPath("$.affectedAppointments").value(0)).andReturn().getResponse().getContentAsString();

		assertTrue(slots(5).isEmpty(), "o primeiro dia fechado não tem horário");
		assertTrue(slots(6).isEmpty(), "o último dia fechado (inclusive) não tem horário");
		assertFalse(slots(7).isEmpty(), "o dia seguinte abre normalmente");
		assertFalse(slots(4).isEmpty(), "o dia anterior abre normalmente");

		book(5).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.reason").value("CLOSED_DAY"));
		book(6).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.reason").value("CLOSED_DAY"));
		book(7).andExpect(status().isCreated());

		String id = JsonPath.read(created, "$.id");
		mvc.perform(delete("/api/provider/time-off/" + id).header(HttpHeaders.AUTHORIZATION, tokenA)).andExpect(status().isNoContent());
		assertFalse(slots(5).isEmpty(), "removida a folga, o dia reabre");
		book(5).andExpect(status().isCreated());
	}

	@Test
	void fecharNaoCancelaNadaSozinhoMasContaOsAgendamentosQueJaExistiam() throws Exception {
		String booked = book(8).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		String appointmentId = JsonPath.read(booked, "$.id");

		close(tokenA, day(8), day(8), null).andExpect(status().isCreated()).andExpect(jsonPath("$.affectedAppointments").value(1));

		assertEquals("SCHEDULED", jdbc.queryForObject("SELECT status FROM appointments WHERE id = ?::uuid", String.class, appointmentId), "o agendamento continua de pé");
	}

	@Test
	void remarcarParaUmDiaFechadoEhRecusado() throws Exception {
		String booked = book(2).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		String appointmentId = JsonPath.read(booked, "$.id");
		close(tokenA, day(9), day(9), "Férias").andExpect(status().isCreated());

		mvc.perform(post("/api/appointments/" + appointmentId + "/reschedule").header(HttpHeaders.AUTHORIZATION, tokenTutor).contentType(MediaType.APPLICATION_JSON)
				.content("{\"localStart\":\"" + day(9) + "T11:00:00\"}")).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.reason").value("CLOSED_DAY"));
	}

	@Test
	void listaDeEsperaNaoAceitaDiaFechado() throws Exception {
		close(tokenA, day(3), day(3), null).andExpect(status().isCreated());
		mvc.perform(post("/api/waitlist").header(HttpHeaders.AUTHORIZATION, tokenTutor).contentType(MediaType.APPLICATION_JSON)
				.content("{\"providerId\":\"" + providerA + "\",\"serviceId\":\"" + serviceA + "\",\"petId\":\"" + petOfTutor + "\",\"date\":\"" + day(3) + "\"}"))
				.andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.reason").value("CLOSED_DAY"));
	}

	@Test
	void validaOsDadosEARecusaPeriodosQueSeSobrepoem() throws Exception {
		close(tokenA, day(3), day(2), null).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.dates").exists());
		close(tokenA, day(-3), day(-1), null).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.dates").exists());
		close(tokenA, day(0), day(400), null).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.dates").exists());
		close(tokenA, day(800), day(801), null).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.dates").exists());
		close(tokenA, day(3), day(4), "x".repeat(81)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.reason").exists());

		close(tokenA, day(10), day(12), "  Feriado  ").andExpect(status().isCreated()).andExpect(jsonPath("$.reason").value("Feriado"));
		close(tokenA, day(12), day(13), null).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.dates").exists());
		close(tokenA, day(9), day(10), null).andExpect(status().isBadRequest());
		close(tokenA, day(13), day(14), null).andExpect(status().isCreated()); // colado, sem sobrepor
	}

	@Test
	void umEstudioSoMexeNasPropriasFolgasEQuemNaoEhPrestadorNaoEntra() throws Exception {
		String created = close(tokenA, day(5), day(5), null).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		String id = JsonPath.read(created, "$.id");

		mvc.perform(delete("/api/provider/time-off/" + id).header(HttpHeaders.AUTHORIZATION, tokenB)).andExpect(status().isNotFound());
		mvc.perform(delete("/api/provider/time-off/" + UUID.randomUUID()).header(HttpHeaders.AUTHORIZATION, tokenB)).andExpect(status().isNotFound());
		mvc.perform(get("/api/provider/time-off").header(HttpHeaders.AUTHORIZATION, tokenB)).andExpect(jsonPath("$.length()").value(0));
		mvc.perform(get("/api/provider/time-off").header(HttpHeaders.AUTHORIZATION, tokenA)).andExpect(jsonPath("$.length()").value(1));

		mvc.perform(get("/api/provider/time-off").header(HttpHeaders.AUTHORIZATION, tokenTutor)).andExpect(status().isForbidden());
		close(tokenTutor, day(5), day(5), null).andExpect(status().isForbidden());
		mvc.perform(get("/api/provider/time-off")).andExpect(status().isUnauthorized());

		assertEquals(1, (int) jdbc.queryForObject("SELECT count(*) FROM time_off WHERE provider_id = ?", Integer.class, providerA), "a folga do A continua");
	}

	@Test
	void oTutorVeOsFechamentosDoEstudioEAAgendaDaEquipeTrazOAviso() throws Exception {
		close(tokenA, day(15), day(17), "Férias coletivas").andExpect(status().isCreated());

		mvc.perform(get("/api/catalog/providers/" + providerA + "/closures").header(HttpHeaders.AUTHORIZATION, tokenTutor)).andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].reason").value("Férias coletivas")).andExpect(jsonPath("$[0].startsOn").value(day(15).toString()))
				.andExpect(jsonPath("$[0].endsOn").value(day(17).toString()));
		mvc.perform(get("/api/catalog/providers/" + UUID.randomUUID() + "/closures").header(HttpHeaders.AUTHORIZATION, tokenTutor)).andExpect(status().isNotFound());

		mvc.perform(get("/api/agenda/workplace").header(HttpHeaders.AUTHORIZATION, tokenA)).andExpect(status().isOk()).andExpect(jsonPath("$.closures.length()").value(1))
				.andExpect(jsonPath("$.closures[0].reason").value("Férias coletivas"));
	}

	@Test
	void periodoQueJaTerminouSaiDaListaMasUmEmAndamentoFica() throws Exception {
		UUID past = jdbc.queryForObject("INSERT INTO time_off (provider_id, starts_on, ends_on) VALUES (?, ?, ?) RETURNING id", UUID.class, providerA, day(-10), day(-5));
		jdbc.update("INSERT INTO time_off (provider_id, starts_on, ends_on, reason) VALUES (?, ?, ?, 'Em andamento')", providerA, day(-1), day(1));

		mvc.perform(get("/api/provider/time-off").header(HttpHeaders.AUTHORIZATION, tokenA)).andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].reason").value("Em andamento"));
		assertTrue(past != null);
	}
}
