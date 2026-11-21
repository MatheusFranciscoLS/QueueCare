package com.queuecare.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
 * Lista de espera (ADR 0022): o tutor pede, vê e tira as próprias entradas; a equipe do estúdio vê quem espera um dia (com contato e a duração
 * prevista para o pet), chama e tira. Entrada de outro tutor ou de outro estúdio é inexistente (404). Sem envio e sem reserva automática.
 */
class WaitlistIntegrationTest extends AuthIntegrationTestBase {

	private static final ZoneId SP = ZoneId.of("America/Sao_Paulo");

	UUID ownerA, ownerB, attendantA, tutor, other;
	UUID providerA, providerB, serviceA, serviceB, petOfTutor, petOfOther;
	String tokenA, tokenB, tokenAttendant, tokenTutor, tokenOther;

	@BeforeEach
	void seed() throws Exception {
		ownerA = seedUser("wa@exemplo.test", "PROVIDER", true);
		ownerB = seedUser("wb@exemplo.test", "PROVIDER", true);
		attendantA = seedUser("wat@exemplo.test", "ATTENDANT", true);
		tutor = seedUser("wt@exemplo.test", "CUSTOMER", true);
		other = seedUser("wo@exemplo.test", "CUSTOMER", true);
		jdbc.update("UPDATE users SET phone = '5511912345678' WHERE id = ?", tutor);
		providerA = jdbc.queryForObject("INSERT INTO providers (user_id, business_name, time_zone, minimum_notice_minutes, cancellation_deadline_minutes) "
				+ "VALUES (?, 'Negócio A', 'America/Sao_Paulo', 0, 120) RETURNING id", UUID.class, ownerA);
		providerB = jdbc.queryForObject("INSERT INTO providers (user_id, business_name, time_zone, minimum_notice_minutes, cancellation_deadline_minutes) "
				+ "VALUES (?, 'Negócio B', 'America/Sao_Paulo', 0, 120) RETURNING id", UUID.class, ownerB);
		serviceA = jdbc.queryForObject("INSERT INTO services (provider_id, name, duration_minutes, duration_large_minutes, coat_extra_minutes) VALUES (?, 'Banho', 60, 90, 30) RETURNING id", UUID.class, providerA);
		serviceB = jdbc.queryForObject("INSERT INTO services (provider_id, name, duration_minutes) VALUES (?, 'Banho', 30) RETURNING id", UUID.class, providerB);
		petOfTutor = jdbc.queryForObject("INSERT INTO pets (customer_id, name, species, size, coat) VALUES (?, 'Thor', 'DOG', 'LARGE', 'DOUBLE') RETURNING id", UUID.class, tutor);
		petOfOther = jdbc.queryForObject("INSERT INTO pets (customer_id, name, species) VALUES (?, 'Mel', 'CAT') RETURNING id", UUID.class, other);
		tokenA = tokenOf("wa@exemplo.test");
		tokenB = tokenOf("wb@exemplo.test");
		tokenAttendant = tokenOf("wat@exemplo.test");
		tokenTutor = tokenOf("wt@exemplo.test");
		tokenOther = tokenOf("wo@exemplo.test");
		mvc.perform(post("/api/provider/staff").header(HttpHeaders.AUTHORIZATION, tokenA).contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"wat@exemplo.test\"}"))
				.andExpect(status().isCreated());
	}

	private String tokenOf(String email) throws Exception {
		String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(json("email", email, "password", PASSWORD)))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		return "Bearer " + JsonPath.read(body, "$.accessToken");
	}

	private LocalDate day(int ahead) {
		return LocalDate.now(SP).plusDays(ahead);
	}

	private ResultActions join(String bearer, UUID provider, UUID service, UUID pet, LocalDate date) throws Exception {
		return mvc.perform(post("/api/waitlist").header(HttpHeaders.AUTHORIZATION, bearer).contentType(MediaType.APPLICATION_JSON)
				.content("{\"providerId\":\"" + provider + "\",\"serviceId\":\"" + service + "\",\"petId\":\"" + pet + "\",\"date\":\"" + date + "\"}"));
	}

	private String joined(int ahead) throws Exception {
		return JsonPath.read(join(tokenTutor, providerA, serviceA, petOfTutor, day(ahead)).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
	}

	@Test
	void tutorEntraVeESai() throws Exception {
		String id = joined(2);
		mvc.perform(get("/api/waitlist/mine").header(HttpHeaders.AUTHORIZATION, tokenTutor)).andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(id))
				.andExpect(jsonPath("$[0].businessName").value("Negócio A")).andExpect(jsonPath("$[0].serviceName").value("Banho")).andExpect(jsonPath("$[0].petName").value("Thor"))
				.andExpect(jsonPath("$[0].date").value(day(2).toString()));
		mvc.perform(delete("/api/waitlist/" + id).header(HttpHeaders.AUTHORIZATION, tokenTutor)).andExpect(status().isNoContent());
		mvc.perform(get("/api/waitlist/mine").header(HttpHeaders.AUTHORIZATION, tokenTutor)).andExpect(jsonPath("$").isEmpty());
		assertNotNull(jdbc.queryForObject("SELECT removed_at FROM waitlist_entries WHERE id = ?::uuid", Object.class, id), "tirar marca a saída, não apaga");
		mvc.perform(delete("/api/waitlist/" + id).header(HttpHeaders.AUTHORIZATION, tokenTutor)).andExpect(status().isNotFound());
	}

	@Test
	void pedirDeNovoDevolveAMesmaEntradaSemDuplicar() throws Exception {
		String first = joined(2);
		String second = joined(2);
		assertEquals(first, second);
		assertEquals(1, count("SELECT count(*) FROM waitlist_entries WHERE removed_at IS NULL"));
		// Depois de sair, pode entrar de novo (entrada nova).
		mvc.perform(delete("/api/waitlist/" + first).header(HttpHeaders.AUTHORIZATION, tokenTutor)).andExpect(status().isNoContent());
		String third = joined(2);
		assertFalse(first.equals(third));
	}

	@Test
	void bancoGarantiaUmaEntradaAtivaPorCombinacao() throws Exception {
		joined(2);
		assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
				"INSERT INTO waitlist_entries (provider_id, service_id, customer_id, pet_id, wanted_date) VALUES (?, ?, ?, ?, ?)", providerA, serviceA, tutor, petOfTutor, day(2)));
	}

	@Test
	void recusasDeDadoInvalido() throws Exception {
		join(tokenTutor, providerA, serviceA, petOfTutor, day(-1)).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.reason").value("INVALID_DATE"));
		join(tokenTutor, providerA, serviceA, petOfTutor, day(91)).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.reason").value("INVALID_DATE"));
		join(tokenTutor, providerA, serviceB, petOfTutor, day(2)).andExpect(status().isNotFound()).andExpect(jsonPath("$.reason").value("SERVICE_NOT_FOUND_OR_INACTIVE"));
		join(tokenTutor, UUID.randomUUID(), serviceA, petOfTutor, day(2)).andExpect(status().isNotFound());
		join(tokenTutor, providerA, serviceA, petOfOther, day(2)).andExpect(status().isNotFound()).andExpect(jsonPath("$.reason").value("PET_NOT_FOUND")); // pet de outro tutor
		jdbc.update("UPDATE pets SET active = false WHERE id = ?", petOfTutor);
		join(tokenTutor, providerA, serviceA, petOfTutor, day(2)).andExpect(status().isNotFound()).andExpect(jsonPath("$.reason").value("PET_NOT_FOUND")); // arquivado
		assertEquals(0, count("SELECT count(*) FROM waitlist_entries"));
	}

	@Test
	void limiteDeEntradasAtivasPorTutor() throws Exception {
		for (int i = 1; i <= 10; i++) {
			joined(i);
		}
		join(tokenTutor, providerA, serviceA, petOfTutor, day(11)).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.reason").value("WAITLIST_LIMIT"));
		// Pedir de novo algo que já existe continua funcionando no limite.
		joined(3);
	}

	@Test
	void equipeVeQuemEspera_comContatoEDuracaoParaOPet_eTutorOutroEstudioENaoVeem() throws Exception {
		joined(2);
		join(tokenOther, providerA, serviceA, petOfOther, day(2)).andExpect(status().isCreated());
		String body = mvc.perform(get("/api/agenda/waitlist?date=" + day(2)).header(HttpHeaders.AUTHORIZATION, tokenA)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		assertEquals(2, ((List<?>) JsonPath.read(body, "$")).size());
		assertEquals("Thor", JsonPath.read(body, "$[0].petName"), "ordem de entrada");
		assertEquals("5511912345678", JsonPath.read(body, "$[0].customerPhone"));
		assertEquals(120, (int) JsonPath.read(body, "$[0].durationMinutes"), "grande (90) + pelo duplo (30)");
		assertEquals(60, (int) JsonPath.read(body, "$[1].durationMinutes"), "gato sem porte nem pelo: a base");
		assertNull(JsonPath.read(body, "$[0].calledAt"));
		mvc.perform(get("/api/agenda/waitlist?date=" + day(2)).header(HttpHeaders.AUTHORIZATION, tokenAttendant)).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2));
		mvc.perform(get("/api/agenda/waitlist?date=" + day(2)).header(HttpHeaders.AUTHORIZATION, tokenB)).andExpect(status().isOk()).andExpect(jsonPath("$").isEmpty());
		mvc.perform(get("/api/agenda/waitlist?date=" + day(2)).header(HttpHeaders.AUTHORIZATION, tokenTutor)).andExpect(status().isForbidden());
		mvc.perform(get("/api/agenda/waitlist?date=" + day(3)).header(HttpHeaders.AUTHORIZATION, tokenA)).andExpect(jsonPath("$").isEmpty());
	}

	@Test
	void equipeChamaETira_outroEstudioENaoMexe() throws Exception {
		String id = joined(2);
		mvc.perform(post("/api/waitlist/" + id + "/called").header(HttpHeaders.AUTHORIZATION, tokenB)).andExpect(status().isNotFound());
		mvc.perform(post("/api/waitlist/" + id + "/called").header(HttpHeaders.AUTHORIZATION, tokenTutor)).andExpect(status().isForbidden());
		mvc.perform(post("/api/waitlist/" + id + "/called").header(HttpHeaders.AUTHORIZATION, tokenAttendant)).andExpect(status().isOk());
		String body = mvc.perform(get("/api/agenda/waitlist?date=" + day(2)).header(HttpHeaders.AUTHORIZATION, tokenA)).andReturn().getResponse().getContentAsString();
		assertNotNull(JsonPath.read(body, "$[0].calledAt"));
		mvc.perform(delete("/api/waitlist/" + id).header(HttpHeaders.AUTHORIZATION, tokenB)).andExpect(status().isNotFound());
		mvc.perform(delete("/api/waitlist/" + id).header(HttpHeaders.AUTHORIZATION, tokenOther)).andExpect(status().isNotFound()); // outro tutor
		assertEquals(1, count("SELECT count(*) FROM waitlist_entries WHERE removed_at IS NULL"));
		mvc.perform(delete("/api/waitlist/" + id).header(HttpHeaders.AUTHORIZATION, tokenA)).andExpect(status().isNoContent());
		mvc.perform(get("/api/waitlist/mine").header(HttpHeaders.AUTHORIZATION, tokenTutor)).andExpect(jsonPath("$").isEmpty());
	}

	@Test
	void tutorSoVeAsProprias() throws Exception {
		joined(2);
		mvc.perform(get("/api/waitlist/mine").header(HttpHeaders.AUTHORIZATION, tokenOther)).andExpect(status().isOk()).andExpect(jsonPath("$").isEmpty());
		mvc.perform(get("/api/waitlist/mine").header(HttpHeaders.AUTHORIZATION, tokenA)).andExpect(status().isForbidden());
	}

	@Test
	void quemJaAgendouNaqueleDiaSaiDaListaSemApagarNada() throws Exception {
		joined(2);
		OffsetDateTime start = day(2).atTime(10, 0).atZone(SP).toOffsetDateTime();
		String appointment = jdbc.queryForObject("INSERT INTO appointments (provider_id, service_id, customer_id, starts_at, ends_at, status, pet_id, pet_name, pet_species) "
				+ "VALUES (?, ?, ?, ?, ?, 'SCHEDULED', ?, 'Thor', 'DOG') RETURNING id::text", String.class, providerA, serviceA, tutor, start, start.plusMinutes(60), petOfTutor);
		mvc.perform(get("/api/agenda/waitlist?date=" + day(2)).header(HttpHeaders.AUTHORIZATION, tokenA)).andExpect(jsonPath("$").isEmpty());
		mvc.perform(get("/api/waitlist/mine").header(HttpHeaders.AUTHORIZATION, tokenTutor)).andExpect(jsonPath("$").isEmpty());
		assertEquals(1, count("SELECT count(*) FROM waitlist_entries WHERE removed_at IS NULL"), "a entrada continua; só a lista não a mostra");
		// Se o agendamento for cancelado, a pessoa volta a aparecer na lista.
		jdbc.update("UPDATE appointments SET status = 'CANCELLED' WHERE id = ?::uuid", appointment);
		mvc.perform(get("/api/agenda/waitlist?date=" + day(2)).header(HttpHeaders.AUTHORIZATION, tokenA)).andExpect(jsonPath("$.length()").value(1));
	}
}
