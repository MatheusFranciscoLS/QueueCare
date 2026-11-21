package com.queuecare.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Lembrete pelo WhatsApp (ADR 0021, fatia 3): a equipe abre o WhatsApp com a mensagem pronta e o agendamento guarda QUANDO, para ninguém
 * lembrar duas vezes. Só a equipe do estúdio, só de quem está agendado e ainda não começou; sem evento no histórico; a agenda do dia mostra o
 * quando, e o prazo de cancelamento do estúdio vem na resposta do local de trabalho (a mensagem diz até quando mudar de graça).
 */
class ReminderIntegrationTest extends AuthIntegrationTestBase {

	private static final ZoneId SP = ZoneId.of("America/Sao_Paulo");

	UUID ownerA, ownerB, attendantA, customer;
	UUID providerA, serviceA;
	String tokenA, tokenB, tokenAttendant, tokenCustomer;
	private int seq;

	@BeforeEach
	void seed() throws Exception {
		ownerA = seedUser("ra@exemplo.test", "PROVIDER", true);
		ownerB = seedUser("rb@exemplo.test", "PROVIDER", true);
		attendantA = seedUser("rat@exemplo.test", "ATTENDANT", true);
		customer = seedUser("rc@exemplo.test", "CUSTOMER", true);
		providerA = jdbc.queryForObject("INSERT INTO providers (user_id, business_name, time_zone, minimum_notice_minutes, cancellation_deadline_minutes) "
				+ "VALUES (?, 'Negócio A', 'America/Sao_Paulo', 0, 720) RETURNING id", UUID.class, ownerA);
		jdbc.update("INSERT INTO providers (user_id, business_name, time_zone, minimum_notice_minutes, cancellation_deadline_minutes) VALUES (?, 'Negócio B', 'America/Sao_Paulo', 0, 120)", ownerB);
		serviceA = jdbc.queryForObject("INSERT INTO services (provider_id, name, duration_minutes) VALUES (?, 'Banho', 30) RETURNING id", UUID.class, providerA);
		tokenA = tokenOf("ra@exemplo.test");
		tokenB = tokenOf("rb@exemplo.test");
		tokenAttendant = tokenOf("rat@exemplo.test");
		tokenCustomer = tokenOf("rc@exemplo.test");
		mvc.perform(post("/api/provider/staff").header(HttpHeaders.AUTHORIZATION, tokenA).contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"rat@exemplo.test\"}"))
				.andExpect(status().isCreated());
	}

	private String tokenOf(String email) throws Exception {
		String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(json("email", email, "password", PASSWORD)))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		return "Bearer " + JsonPath.read(body, "$.accessToken");
	}

	private ResultActions remind(String bearer, String id) throws Exception {
		return mvc.perform(post("/api/appointments/" + id + "/reminded").header(HttpHeaders.AUTHORIZATION, bearer));
	}

	private String appointment(long hoursFromNow, String statusName) {
		long days = ++seq * (hoursFromNow < 0 ? -1 : 1);
		OffsetDateTime start = OffsetDateTime.now().plusHours(hoursFromNow).plusDays(days).withSecond(0).withNano(0);
		return jdbc.queryForObject("INSERT INTO appointments (provider_id, service_id, customer_id, starts_at, ends_at, status, pet_id, pet_name, pet_species) "
				+ "VALUES (?, ?, ?, ?, ?, ?, ?, 'Pet de Teste', 'DOG') RETURNING id::text", String.class, providerA, serviceA, customer, start, start.plusMinutes(30), statusName, petOf(customer));
	}

	private Object remindedAt(String id) {
		return jdbc.queryForObject("SELECT reminded_at FROM appointments WHERE id = ?::uuid", Object.class, id);
	}

	@Test
	void equipeMarcaOLembreteEOHorarioFicaGuardadoSemEventoNoHistorico() throws Exception {
		String id = appointment(24, "SCHEDULED");
		assertNull(remindedAt(id));
		remind(tokenA, id).andExpect(status().isOk());
		assertNotNull(remindedAt(id));
		assertEquals(0, count("SELECT count(*) FROM appointment_events WHERE appointment_id = '" + id + "'::uuid"), "lembrete não é evento do histórico");
		assertEquals("SCHEDULED", jdbc.queryForObject("SELECT status FROM appointments WHERE id = ?::uuid", String.class, id), "lembrar não muda a situação");
	}

	@Test
	void atendenteVinculadoTambemMarcaEPodeLembrarDeNovoDeProposito() throws Exception {
		String id = appointment(24, "SCHEDULED");
		remind(tokenAttendant, id).andExpect(status().isOk());
		Object first = remindedAt(id);
		remind(tokenA, id).andExpect(status().isOk());
		assertNotNull(first);
		assertNotNull(remindedAt(id));
	}

	@Test
	void soDeQuemEstaAgendadoEAindaNaoComecou() throws Exception {
		for (String other : new String[] { "CHECKED_IN", "IN_PROGRESS", "READY", "COMPLETED", "NO_SHOW" }) {
			String id = appointment(-3, other);
			remind(tokenA, id).andExpect(status().isConflict()).andExpect(jsonPath("$.reason").value("INVALID_STATE"));
			assertNull(remindedAt(id));
		}
		String cancelled = appointment(24, "CANCELLED");
		remind(tokenA, cancelled).andExpect(status().isConflict()).andExpect(jsonPath("$.reason").value("ALREADY_CANCELLED"));
		String started = appointment(-1, "SCHEDULED"); // o horário já passou: lembrar não faz mais sentido
		remind(tokenA, started).andExpect(status().isConflict()).andExpect(jsonPath("$.reason").value("INVALID_STATE"));
		assertNull(remindedAt(started));
	}

	@Test
	void tutorOutroEstudioENaoExistenteNaoMarcam() throws Exception {
		String id = appointment(24, "SCHEDULED");
		remind(tokenCustomer, id).andExpect(status().isForbidden());
		remind(tokenB, id).andExpect(status().isNotFound());
		remind(tokenA, UUID.randomUUID().toString()).andExpect(status().isNotFound());
		assertNull(remindedAt(id));
	}

	@Test
	void agendaDoDiaMostraQuandoFoiLembradoEOLocalDeTrabalhoTrazOPrazo() throws Exception {
		LocalDate today = LocalDate.now(SP);
		OffsetDateTime start = today.plusDays(1).atTime(10, 0).atZone(SP).toOffsetDateTime();
		String id = jdbc.queryForObject("INSERT INTO appointments (provider_id, service_id, customer_id, starts_at, ends_at, status, pet_id, pet_name, pet_species) "
				+ "VALUES (?, ?, ?, ?, ?, 'SCHEDULED', ?, 'Rex', 'DOG') RETURNING id::text", String.class, providerA, serviceA, customer, start, start.plusMinutes(30), petOf(customer));
		String before = mvc.perform(get("/api/agenda?date=" + today.plusDays(1)).header(HttpHeaders.AUTHORIZATION, tokenA)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		assertNull(JsonPath.read(before, "$[0].remindedAt"));
		remind(tokenA, id).andExpect(status().isOk());
		String after = mvc.perform(get("/api/agenda?date=" + today.plusDays(1)).header(HttpHeaders.AUTHORIZATION, tokenA)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		assertNotNull(JsonPath.read(after, "$[0].remindedAt"));
		// A semana, de várias linhas, não leva o dado (como o telefone).
		String week = mvc.perform(get("/api/agenda/week?start=" + today).header(HttpHeaders.AUTHORIZATION, tokenA)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		assertNull(JsonPath.read(week, "$[0].remindedAt"));
		mvc.perform(get("/api/agenda/workplace").header(HttpHeaders.AUTHORIZATION, tokenA)).andExpect(jsonPath("$.cancellationDeadlineMinutes").value(720));
		mvc.perform(get("/api/agenda/workplace").header(HttpHeaders.AUTHORIZATION, tokenAttendant)).andExpect(jsonPath("$.cancellationDeadlineMinutes").value(720));
	}
}
