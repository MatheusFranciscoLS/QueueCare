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
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

/**
 * Ficha do pet (ADR 0019, fatia 2): tipo de pelo, peso, comportamento, saúde e vacina. Cobre o cadastro e a edição pela API, a validação
 * (sem repetir o valor), o banco como última barreira, a CÓPIA no agendamento e o que cada perfil enxerga.
 */
class PetFichaIntegrationTest extends AuthIntegrationTestBase {

	static final String BASE = "/api/customer/pets";

	UUID tutor, ownerA, ownerB;
	UUID providerA, serviceA;
	String tokenTutor, tokenA, tokenB;

	@BeforeEach
	void seed() throws Exception {
		tutor = seedUser("ft@exemplo.test", "CUSTOMER", true);
		ownerA = seedUser("fa@exemplo.test", "PROVIDER", true);
		ownerB = seedUser("fb@exemplo.test", "PROVIDER", true);
		providerA = jdbc.queryForObject("INSERT INTO providers (user_id, business_name, time_zone, minimum_notice_minutes, cancellation_deadline_minutes) "
				+ "VALUES (?, 'Negócio A', 'America/Sao_Paulo', 0, 120) RETURNING id", UUID.class, ownerA);
		jdbc.update("INSERT INTO providers (user_id, business_name, time_zone, minimum_notice_minutes, cancellation_deadline_minutes) VALUES (?, 'Negócio B', 'America/Sao_Paulo', 0, 120)", ownerB);
		serviceA = jdbc.queryForObject("INSERT INTO services (provider_id, name, duration_minutes) VALUES (?, 'Banho', 60) RETURNING id", UUID.class, providerA);
		tokenTutor = tokenOf("ft@exemplo.test");
		tokenA = tokenOf("fa@exemplo.test");
		tokenB = tokenOf("fb@exemplo.test");
	}

	private String tokenOf(String email) throws Exception {
		String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(json("email", email, "password", PASSWORD)))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		return "Bearer " + JsonPath.read(body, "$.accessToken");
	}

	private static final String FICHA = "\"coat\":\"DOUBLE\",\"weightKg\":32.5,\"temperament\":\"REACTIVE\",\"healthNotes\":\"Alergia a shampoo de aveia\",\"vaccinated\":true";

	private String create(String body) throws Exception {
		return mvc.perform(post(BASE).header(HttpHeaders.AUTHORIZATION, tokenTutor).contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
	}

	private String createFull() throws Exception {
		return JsonPath.read(create("{\"name\":\"Thor\",\"species\":\"DOG\",\"breed\":\"Golden Retriever\",\"size\":\"LARGE\"," + FICHA + "}"), "$.id");
	}

	private String schedule(String petId, String localStart, String statusName) {
		ZonedDateTime start = java.time.LocalDateTime.parse(localStart).atZone(ZoneId.of("America/Sao_Paulo"));
		String id = jdbc.queryForObject("INSERT INTO appointments (provider_id, service_id, customer_id, starts_at, ends_at, status, pet_id, pet_name, pet_species, "
				+ "pet_coat, pet_weight_kg, pet_temperament, pet_health_notes, pet_vaccinated) SELECT ?, ?, ?, ?, ?, ?, p.id, p.name, p.species, p.coat, p.weight_kg, "
				+ "p.temperament, p.health_notes, p.vaccinated FROM pets p WHERE p.id = ?::uuid RETURNING id::text", String.class, providerA, serviceA, tutor,
				java.sql.Timestamp.from(start.toInstant()), java.sql.Timestamp.from(start.plusHours(1).toInstant()), statusName, petId);
		return id;
	}

	@Test
	void cadastraComFichaEDevolveTudo() throws Exception {
		String body = create("{\"name\":\"Thor\",\"species\":\"DOG\"," + FICHA + "}");
		assertEquals("DOUBLE", JsonPath.read(body, "$.coat"));
		assertEquals(32.5, ((Number) JsonPath.read(body, "$.weightKg")).doubleValue());
		assertEquals("REACTIVE", JsonPath.read(body, "$.temperament"));
		assertEquals("Alergia a shampoo de aveia", JsonPath.read(body, "$.healthNotes"));
		assertEquals(true, JsonPath.read(body, "$.vaccinated"));
	}

	@Test
	void fichaEhOpcionalEPetSemFichaContinuaValendo() throws Exception {
		String body = create("{\"name\":\"Mel\",\"species\":\"CAT\"}");
		assertNull(JsonPath.read(body, "$.coat"));
		assertNull(JsonPath.read(body, "$.weightKg"));
		assertNull(JsonPath.read(body, "$.temperament"));
		assertNull(JsonPath.read(body, "$.healthNotes"));
		assertNull(JsonPath.read(body, "$.vaccinated"));
	}

	@Test
	void pesoNormalizaParaUmaCasaEAceitaOsLimites() throws Exception {
		assertEquals(12.0, ((Number) JsonPath.read(create("{\"name\":\"A\",\"species\":\"DOG\",\"weightKg\":12}"), "$.weightKg")).doubleValue());
		assertEquals(0.5, ((Number) JsonPath.read(create("{\"name\":\"B\",\"species\":\"CAT\",\"weightKg\":0.5}"), "$.weightKg")).doubleValue());
		assertEquals(120.0, ((Number) JsonPath.read(create("{\"name\":\"C\",\"species\":\"DOG\",\"weightKg\":120.00}"), "$.weightKg")).doubleValue());
	}

	@Test
	void validacaoRecusaFichaInvalidaPorCampoSemRepetirOValor() throws Exception {
		String body = mvc.perform(post(BASE).header(HttpHeaders.AUTHORIZATION, tokenTutor).contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\":\"Thor\",\"species\":\"DOG\",\"coat\":\"CRESPO-SECRETO\",\"weightKg\":0.4,\"temperament\":\"BRAVO\",\"healthNotes\":\"" + "x".repeat(301) + "\"}"))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.coat").exists()).andExpect(jsonPath("$.errors.weightKg").exists())
				.andExpect(jsonPath("$.errors.temperament").exists()).andExpect(jsonPath("$.errors.healthNotes").exists()).andReturn().getResponse().getContentAsString();
		assertFalse(body.contains("CRESPO-SECRETO"));
		for (String weight : new String[] { "120.1", "12.25", "-3", "0" }) {
			mvc.perform(post(BASE).header(HttpHeaders.AUTHORIZATION, tokenTutor).contentType(MediaType.APPLICATION_JSON)
					.content("{\"name\":\"Thor\",\"species\":\"DOG\",\"weightKg\":" + weight + "}")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.weightKg").exists());
		}
		assertEquals(0, count("SELECT count(*) FROM pets"), "nada é gravado");
	}

	@Test
	void editarSubstituiAFichaInclusiveLimpando() throws Exception {
		String id = createFull();
		mvc.perform(put(BASE + "/" + id).header(HttpHeaders.AUTHORIZATION, tokenTutor).contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\":\"Thor\",\"species\":\"DOG\",\"coat\":\"SHORT\",\"vaccinated\":false}")).andExpect(status().isOk())
				.andExpect(jsonPath("$.coat").value("SHORT")).andExpect(jsonPath("$.vaccinated").value(false)).andExpect(jsonPath("$.weightKg").doesNotExist())
				.andExpect(jsonPath("$.temperament").doesNotExist()).andExpect(jsonPath("$.healthNotes").doesNotExist());
	}

	@Test
	void bancoRecusaFichaForaDaRegra() throws Exception {
		String id = createFull();
		for (String set : new String[] { "coat = 'SEDOSO'", "weight_kg = 0.4", "weight_kg = 120.1", "temperament = 'BRAVO'", "health_notes = ''", "health_notes = ' x'",
				"health_notes = E'a\\tb'", "health_notes = repeat('x', 301)" }) {
			assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("UPDATE pets SET " + set + " WHERE id = ?::uuid", id), set);
		}
	}

	@Test
	void bancoGarantiaACopiaSoComPet() throws Exception {
		String id = createFull();
		String appointment = schedule(id, "2027-03-02T10:00:00", "SCHEDULED");
		assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("UPDATE appointments SET pet_coat = 'SEDOSO' WHERE id = ?::uuid", appointment));
		assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("UPDATE appointments SET pet_weight_kg = 200 WHERE id = ?::uuid", appointment));
		// agendamento ANTIGO sem pet não pode ter ficha solta (a consistência do V9 cobre os campos novos)
		assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("UPDATE appointments SET pet_id = NULL, pet_name = NULL, pet_species = NULL, pet_breed = NULL, pet_size = NULL, "
				+ "pet_notes = NULL WHERE id = ?::uuid", appointment));
	}

	@Test
	void agendaDoDiaMostraAFichaDaCopiaEASemanaNao() throws Exception {
		String petId = createFull();
		schedule(petId, "2027-03-02T10:00:00", "SCHEDULED");
		String day = mvc.perform(get("/api/agenda").param("date", "2027-03-02").header(HttpHeaders.AUTHORIZATION, tokenA)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		assertEquals("DOUBLE", JsonPath.read(day, "$[0].pet.coat"));
		assertEquals(32.5, ((Number) JsonPath.read(day, "$[0].pet.weightKg")).doubleValue());
		assertEquals("REACTIVE", JsonPath.read(day, "$[0].pet.temperament"));
		assertEquals("Alergia a shampoo de aveia", JsonPath.read(day, "$[0].pet.healthNotes"));
		assertEquals(true, JsonPath.read(day, "$[0].pet.vaccinated"));
		String week = mvc.perform(get("/api/agenda/week").param("start", "2027-03-01").header(HttpHeaders.AUTHORIZATION, tokenA)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		assertFalse(week.contains("aveia"), "a semana não leva a ficha");
		assertNull(JsonPath.read(week, "$[0].pet.coat"));
		assertNull(JsonPath.read(week, "$[0].pet.temperament"));
	}

	@Test
	void outroPrestadorNaoVeAFicha() throws Exception {
		schedule(createFull(), "2027-03-02T10:00:00", "SCHEDULED");
		String other = mvc.perform(get("/api/agenda").param("date", "2027-03-02").header(HttpHeaders.AUTHORIZATION, tokenB)).andReturn().getResponse().getContentAsString();
		assertFalse(other.contains("aveia"));
		assertFalse(other.contains("REACTIVE"));
	}

	@Test
	void editarOPetAtualizaACopiaEnquantoAgendadoEDepoisCongela() throws Exception {
		String petId = createFull();
		String scheduled = schedule(petId, "2027-03-02T10:00:00", "SCHEDULED");
		String arrived = schedule(petId, "2027-03-03T10:00:00", "CHECKED_IN");
		mvc.perform(put(BASE + "/" + petId).header(HttpHeaders.AUTHORIZATION, tokenTutor).contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\":\"Thor\",\"species\":\"DOG\",\"coat\":\"LONG\",\"weightKg\":30,\"temperament\":\"CALM\",\"healthNotes\":\"Ferida na pata\",\"vaccinated\":false}"))
				.andExpect(status().isOk());
		assertEquals("LONG", jdbc.queryForObject("SELECT pet_coat FROM appointments WHERE id = ?::uuid", String.class, scheduled));
		assertEquals("Ferida na pata", jdbc.queryForObject("SELECT pet_health_notes FROM appointments WHERE id = ?::uuid", String.class, scheduled));
		assertEquals("CALM", jdbc.queryForObject("SELECT pet_temperament FROM appointments WHERE id = ?::uuid", String.class, scheduled));
		assertEquals("DOUBLE", jdbc.queryForObject("SELECT pet_coat FROM appointments WHERE id = ?::uuid", String.class, arrived), "quem já chegou fica congelado");
		assertEquals("REACTIVE", jdbc.queryForObject("SELECT pet_temperament FROM appointments WHERE id = ?::uuid", String.class, arrived));
	}

	@Test
	void reservaPelaApiCopiaAFichaParaOAgendamento() throws Exception {
		String petId = createFull();
		jdbc.update("INSERT INTO availability_windows (provider_id, day_of_week, start_time, end_time) SELECT ?, d, '08:00', '18:00' FROM generate_series(1, 7) d", providerA);
		String day = java.time.LocalDate.now(ZoneId.of("America/Sao_Paulo")).plusDays(3) + "T10:00:00";
		mvc.perform(post("/api/appointments").header(HttpHeaders.AUTHORIZATION, tokenTutor).contentType(MediaType.APPLICATION_JSON)
				.content("{\"providerId\":\"" + providerA + "\",\"serviceId\":\"" + serviceA + "\",\"petId\":\"" + petId + "\",\"localStart\":\"" + day + "\"}"))
				.andExpect(status().isCreated());
		assertEquals("DOUBLE", jdbc.queryForObject("SELECT pet_coat FROM appointments", String.class));
		assertEquals("REACTIVE", jdbc.queryForObject("SELECT pet_temperament FROM appointments", String.class));
		assertEquals("Alergia a shampoo de aveia", jdbc.queryForObject("SELECT pet_health_notes FROM appointments", String.class));
		assertEquals(true, jdbc.queryForObject("SELECT pet_vaccinated FROM appointments", Boolean.class));
	}
}
