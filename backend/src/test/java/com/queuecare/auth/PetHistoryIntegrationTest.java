package com.queuecare.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

/**
 * Histórico do pet (ADR 0019, fatia 6). Tutor: as visitas do PRÓPRIO pet (todas as situações, valor congelado). Equipe: as visitas anteriores
 * CONCLUÍDAS do mesmo pet no SEU prestador, a partir de um agendamento. Isolamento por dono e por prestador, com tokens e banco reais.
 */
class PetHistoryIntegrationTest extends AuthIntegrationTestBase {

	private static final ZoneId SP = ZoneId.of("America/Sao_Paulo");

	UUID tutor, otherTutor, ownerA, ownerB, attendantA;
	UUID providerA, providerB, serviceA, serviceB, pet, otherPet;
	String tokenTutor, tokenOther, tokenA, tokenB, tokenAttendant;

	@BeforeEach
	void seed() throws Exception {
		tutor = seedUser("ht@exemplo.test", "CUSTOMER", true);
		otherTutor = seedUser("ho@exemplo.test", "CUSTOMER", true);
		ownerA = seedUser("ha@exemplo.test", "PROVIDER", true);
		ownerB = seedUser("hb@exemplo.test", "PROVIDER", true);
		attendantA = seedUser("hat@exemplo.test", "ATTENDANT", true);
		providerA = business(ownerA, "Estúdio A");
		providerB = business(ownerB, "Estúdio B");
		serviceA = service(providerA, "Banho");
		serviceB = service(providerB, "Tosa");
		pet = petOf(tutor);
		otherPet = petOf(otherTutor);
		tokenTutor = tokenOf("ht@exemplo.test");
		tokenOther = tokenOf("ho@exemplo.test");
		tokenA = tokenOf("ha@exemplo.test");
		tokenB = tokenOf("hb@exemplo.test");
		tokenAttendant = tokenOf("hat@exemplo.test");
		mvc.perform(post("/api/provider/staff").header(HttpHeaders.AUTHORIZATION, tokenA).contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"hat@exemplo.test\"}"))
				.andExpect(status().isCreated());
	}

	private UUID business(UUID owner, String name) {
		return jdbc.queryForObject("INSERT INTO providers (user_id, business_name, time_zone, minimum_notice_minutes, cancellation_deadline_minutes) "
				+ "VALUES (?, ?, 'America/Sao_Paulo', 0, 120) RETURNING id", UUID.class, owner, name);
	}

	private UUID service(UUID provider, String name) {
		return jdbc.queryForObject("INSERT INTO services (provider_id, name, duration_minutes) VALUES (?, ?, 60) RETURNING id", UUID.class, provider, name);
	}

	private String tokenOf(String email) throws Exception {
		String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(json("email", email, "password", PASSWORD)))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		return "Bearer " + JsonPath.read(body, "$.accessToken");
	}

	/** Agendamento do pet {@code petId} no prestador/serviço, no dia {@code dayOffset} (relativo a hoje), às 10h de São Paulo. */
	private String visit(UUID customer, UUID petId, UUID provider, UUID service, int dayOffset, String statusName, Integer price) {
		OffsetDateTime start = LocalDate.now(SP).plusDays(dayOffset).atTime(10, 0).atZone(SP).toOffsetDateTime();
		return jdbc.queryForObject("INSERT INTO appointments (provider_id, service_id, customer_id, starts_at, ends_at, status, pet_id, pet_name, pet_species, price_cents) "
				+ "VALUES (?, ?, ?, ?, ?, ?, ?, 'Pet', 'DOG', ?) RETURNING id::text", String.class, provider, service, customer, start, start.plusHours(1), statusName, petId, price);
	}

	@Test
	void tutorVeAsVisitasDoPetDaMaisRecenteParaAMaisAntigaComValorEEstudio() throws Exception {
		visit(tutor, pet, providerA, serviceA, -30, "COMPLETED", 5000);
		visit(tutor, pet, providerB, serviceB, -10, "COMPLETED", 4500);
		visit(tutor, pet, providerA, serviceA, 5, "SCHEDULED", 5000);
		visit(tutor, pet, providerA, serviceA, -20, "CANCELLED", 5000);
		String body = mvc.perform(get("/api/customer/pets/" + pet + "/history").header(HttpHeaders.AUTHORIZATION, tokenTutor)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		assertEquals(List.of("SCHEDULED", "COMPLETED", "CANCELLED", "COMPLETED"), JsonPath.read(body, "$[*].status"));
		assertEquals(List.of("Estúdio A", "Estúdio B", "Estúdio A", "Estúdio A"), JsonPath.read(body, "$[*].businessName"));
		assertEquals(List.of("Banho", "Tosa", "Banho", "Banho"), JsonPath.read(body, "$[*].serviceName"));
		assertEquals(4500, (int) JsonPath.read(body, "$[1].priceCents"));
	}

	@Test
	void petSemVisitasDevolveListaVazia() throws Exception {
		mvc.perform(get("/api/customer/pets/" + pet + "/history").header(HttpHeaders.AUTHORIZATION, tokenTutor)).andExpect(status().isOk()).andExpect(jsonPath("$").isEmpty());
	}

	@Test
	void historicoDePetDeOutroClienteEhIgualAPetInexistente() throws Exception {
		visit(otherTutor, otherPet, providerA, serviceA, -5, "COMPLETED", 5000);
		mvc.perform(get("/api/customer/pets/" + otherPet + "/history").header(HttpHeaders.AUTHORIZATION, tokenTutor)).andExpect(status().isNotFound()).andExpect(jsonPath("$.reason").value("PET_NOT_FOUND"));
		mvc.perform(get("/api/customer/pets/" + UUID.randomUUID() + "/history").header(HttpHeaders.AUTHORIZATION, tokenTutor)).andExpect(status().isNotFound());
	}

	@Test
	void soOClienteAcessaOHistoricoDoCadastro() throws Exception {
		for (String bearer : new String[] { tokenA, tokenAttendant }) {
			mvc.perform(get("/api/customer/pets/" + pet + "/history").header(HttpHeaders.AUTHORIZATION, bearer)).andExpect(status().isForbidden());
		}
		mvc.perform(get("/api/customer/pets/" + pet + "/history")).andExpect(status().isUnauthorized());
	}

	@Test
	void equipeVeSoAsVisitasConcluidasAnterioresDoMesmoPetNoSeuPrestador() throws Exception {
		visit(tutor, pet, providerA, serviceA, -60, "COMPLETED", 5000);
		visit(tutor, pet, providerA, serviceA, -30, "COMPLETED", 5500);
		visit(tutor, pet, providerA, serviceA, -20, "CANCELLED", 5000); // cancelada não conta
		visit(tutor, pet, providerB, serviceB, -15, "COMPLETED", 4500); // outro prestador não aparece
		UUID otherPetOfTutor = petOf(tutor);
		visit(tutor, otherPetOfTutor, providerA, serviceA, -10, "COMPLETED", 3000); // outro pet não aparece
		String today = visit(tutor, pet, providerA, serviceA, 0, "CHECKED_IN", 5500);
		visit(tutor, pet, providerA, serviceA, 7, "SCHEDULED", 5500); // futura não aparece
		for (String bearer : new String[] { tokenA, tokenAttendant }) {
			String body = mvc.perform(get("/api/appointments/" + today + "/pet-visits").header(HttpHeaders.AUTHORIZATION, bearer)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
			assertEquals(List.of("Banho", "Banho"), JsonPath.read(body, "$[*].serviceName"));
			assertEquals(List.of(5500, 5000), JsonPath.read(body, "$[*].priceCents"));
			assertFalse(body.contains("customer"), "nada do tutor");
		}
	}

	@Test
	void naoMostraVisitasPosterioresAoAgendamentoConsultado() throws Exception {
		String old = visit(tutor, pet, providerA, serviceA, -40, "COMPLETED", 5000);
		visit(tutor, pet, providerA, serviceA, -10, "COMPLETED", 5500);
		mvc.perform(get("/api/appointments/" + old + "/pet-visits").header(HttpHeaders.AUTHORIZATION, tokenA)).andExpect(status().isOk()).andExpect(jsonPath("$").isEmpty());
	}

	@Test
	void agendamentoDeOutroPrestadorOuInexistenteEh404EClienteEh403() throws Exception {
		String id = visit(tutor, pet, providerA, serviceA, 0, "CHECKED_IN", 5000);
		mvc.perform(get("/api/appointments/" + id + "/pet-visits").header(HttpHeaders.AUTHORIZATION, tokenB)).andExpect(status().isNotFound());
		mvc.perform(get("/api/appointments/" + UUID.randomUUID() + "/pet-visits").header(HttpHeaders.AUTHORIZATION, tokenA)).andExpect(status().isNotFound());
		mvc.perform(get("/api/appointments/" + id + "/pet-visits").header(HttpHeaders.AUTHORIZATION, tokenTutor)).andExpect(status().isForbidden());
		mvc.perform(get("/api/appointments/" + id + "/pet-visits")).andExpect(status().isUnauthorized());
	}

	@Test
	void limitaAs10VisitasMaisRecentes() throws Exception {
		for (int i = 1; i <= 12; i++) {
			visit(tutor, pet, providerA, serviceA, -i - 1, "COMPLETED", 1000 + i);
		}
		String id = visit(tutor, pet, providerA, serviceA, 0, "CHECKED_IN", 5000);
		String body = mvc.perform(get("/api/appointments/" + id + "/pet-visits").header(HttpHeaders.AUTHORIZATION, tokenA)).andReturn().getResponse().getContentAsString();
		List<Integer> prices = JsonPath.read(body, "$[*].priceCents");
		assertEquals(10, prices.size());
		assertEquals(1001, prices.get(0), "a mais recente primeiro");
	}
}
