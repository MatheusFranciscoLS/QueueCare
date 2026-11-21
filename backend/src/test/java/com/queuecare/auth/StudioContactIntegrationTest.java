package com.queuecare.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

/**
 * Política visível e contato do estúdio (ADR 0021, fatia 1): o estúdio cadastra o WhatsApp (formato único, o banco recusa o resto), o catálogo e o
 * agendamento do tutor trazem o prazo e o telefone, e {@code freeChangeUntil} diz até quando cancelar ou remarcar sem falar com o estúdio.
 */
class StudioContactIntegrationTest extends AuthIntegrationTestBase {

	UUID owner, tutor, providerId;
	String tokenOwner, tokenTutor;

	@BeforeEach
	void seed() throws Exception {
		owner = seedUser("sc@exemplo.test", "PROVIDER", true);
		tutor = seedUser("sct@exemplo.test", "CUSTOMER", true);
		providerId = jdbc.queryForObject("INSERT INTO providers (user_id, business_name, time_zone, minimum_notice_minutes, cancellation_deadline_minutes) "
				+ "VALUES (?, 'Negócio', 'America/Sao_Paulo', 0, 720) RETURNING id", UUID.class, owner);
		jdbc.update("INSERT INTO availability_windows (provider_id, day_of_week, start_time, end_time) SELECT ?, d, '08:00', '18:00' FROM generate_series(1, 7) d", providerId);
		tokenOwner = tokenOf("sc@exemplo.test");
		tokenTutor = tokenOf("sct@exemplo.test");
	}

	private String tokenOf(String email) throws Exception {
		String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(json("email", email, "password", PASSWORD)))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		return "Bearer " + JsonPath.read(body, "$.accessToken");
	}

	private String putBusiness(String phoneJson) throws Exception {
		return mvc.perform(put("/api/provider").header(HttpHeaders.AUTHORIZATION, tokenOwner).contentType(MediaType.APPLICATION_JSON)
				.content("{\"businessName\":\"Negócio\",\"timeZone\":\"America/Sao_Paulo\",\"minimumNoticeMinutes\":0,\"cancellationDeadlineMinutes\":720" + phoneJson + "}"))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
	}

	private String bookOneAppointment() throws Exception {
		String service = JsonPath.read(mvc.perform(post("/api/provider/services").header(HttpHeaders.AUTHORIZATION, tokenOwner).contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\":\"Banho\",\"durationMinutes\":60}")).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
		UUID pet = jdbc.queryForObject("INSERT INTO pets (customer_id, name, species) VALUES (?, 'Rex', 'DOG') RETURNING id", UUID.class, tutor);
		String start = LocalDate.now(ZoneId.of("America/Sao_Paulo")).plusDays(3) + "T10:00:00";
		return mvc.perform(post("/api/appointments").header(HttpHeaders.AUTHORIZATION, tokenTutor).contentType(MediaType.APPLICATION_JSON)
				.content("{\"providerId\":\"" + providerId + "\",\"serviceId\":\"" + service + "\",\"petId\":\"" + pet + "\",\"localStart\":\"" + start + "\"}"))
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
	}

	@Test
	void estudioCadastraOWhatsAppComMascaraEAApiGuardaSoDigitosComDdi() throws Exception {
		String body = putBusiness(",\"phone\":\"(11) 91234-5678\"");
		assertEquals("5511912345678", JsonPath.read(body, "$.phone"));
		mvc.perform(get("/api/provider").header(HttpHeaders.AUTHORIZATION, tokenOwner)).andExpect(jsonPath("$.phone").value("5511912345678"));
		assertEquals("5511912345678", jdbc.queryForObject("SELECT phone FROM providers", String.class));
	}

	@Test
	void vazioOuAusenteLimpaOTelefone() throws Exception {
		putBusiness(",\"phone\":\"11912345678\"");
		assertNull(JsonPath.read(putBusiness(",\"phone\":\"\""), "$.phone"));
		putBusiness(",\"phone\":\"11912345678\"");
		assertNull(JsonPath.read(putBusiness(""), "$.phone"), "sem o campo, limpa (a tela envia o valor atual sempre)");
		assertNull(jdbc.queryForObject("SELECT phone FROM providers", String.class));
	}

	@Test
	void telefoneInvalidoEh400SemRepetirOValorEBancoRecusaOResto() throws Exception {
		String response = mvc.perform(put("/api/provider").header(HttpHeaders.AUTHORIZATION, tokenOwner).contentType(MediaType.APPLICATION_JSON)
				.content("{\"businessName\":\"Negócio\",\"timeZone\":\"America/Sao_Paulo\",\"minimumNoticeMinutes\":0,\"cancellationDeadlineMinutes\":720,\"phone\":\"12345\"}"))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.phone").exists()).andReturn().getResponse().getContentAsString();
		assertFalse(response.contains("12345"), "o telefone é dado pessoal: o erro não o repete");
		for (String bad : new String[] { "5501912345678", "5511912345", "11912345678", "+5511912345678" }) {
			assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("UPDATE providers SET phone = ?", bad), bad);
		}
	}

	@Test
	void catalogoMostraOPrazoEOTelefoneAntesDeReservar() throws Exception {
		putBusiness(",\"phone\":\"11912345678\"");
		mvc.perform(get("/api/catalog/providers").header(HttpHeaders.AUTHORIZATION, tokenTutor)).andExpect(status().isOk())
				.andExpect(jsonPath("$[0].cancellationDeadlineMinutes").value(720)).andExpect(jsonPath("$[0].phone").value("5511912345678"));
	}

	@Test
	void agendamentoDoTutorTrazOLimiteGratisEOTelefoneDoEstudio() throws Exception {
		putBusiness(",\"phone\":\"11912345678\"");
		String booked = bookOneAppointment();
		Instant starts = Instant.parse(JsonPath.read(booked, "$.startsAt"));
		assertEquals(starts.minus(Duration.ofMinutes(720)), Instant.parse(JsonPath.read(booked, "$.freeChangeUntil")), "início menos o prazo do estúdio");
		assertEquals("5511912345678", JsonPath.read(booked, "$.studioPhone"));
		mvc.perform(get("/api/appointments/mine").header(HttpHeaders.AUTHORIZATION, tokenTutor)).andExpect(jsonPath("$[0].freeChangeUntil").exists())
				.andExpect(jsonPath("$[0].studioPhone").value("5511912345678"));
	}

	@Test
	void cancelarTiraOLimiteDeGraca() throws Exception {
		String id = JsonPath.read(bookOneAppointment(), "$.id");
		mvc.perform(post("/api/appointments/" + id + "/cancel").header(HttpHeaders.AUTHORIZATION, tokenTutor)).andExpect(status().isOk());
		mvc.perform(get("/api/appointments/mine").header(HttpHeaders.AUTHORIZATION, tokenTutor)).andExpect(jsonPath("$[0].status").value("CANCELLED"))
				.andExpect(jsonPath("$[0].freeChangeUntil").doesNotExist());
	}

	@Test
	void semTelefoneOCampoSaiNulo() throws Exception {
		String booked = bookOneAppointment();
		assertNull(JsonPath.read(booked, "$.studioPhone"));
	}
}
