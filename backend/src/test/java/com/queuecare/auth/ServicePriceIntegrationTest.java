package com.queuecare.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

/**
 * Preço do serviço (ADR 0019, fatia 3): cadastro em centavos pelo prestador, o cliente vê o valor ANTES de reservar, a reserva CONGELA o valor
 * pelo porte do pet e mudar o preço depois não reescreve o histórico. O banco recusa o que está fora da faixa.
 */
class ServicePriceIntegrationTest extends AuthIntegrationTestBase {

	UUID owner, tutor, providerId;
	String tokenOwner, tokenTutor;

	@BeforeEach
	void seed() throws Exception {
		owner = seedUser("sp@exemplo.test", "PROVIDER", true);
		tutor = seedUser("st@exemplo.test", "CUSTOMER", true);
		providerId = jdbc.queryForObject("INSERT INTO providers (user_id, business_name, time_zone, minimum_notice_minutes, cancellation_deadline_minutes) "
				+ "VALUES (?, 'Negócio', 'America/Sao_Paulo', 0, 120) RETURNING id", UUID.class, owner);
		jdbc.update("INSERT INTO availability_windows (provider_id, day_of_week, start_time, end_time) SELECT ?, d, '08:00', '18:00' FROM generate_series(1, 7) d", providerId);
		tokenOwner = tokenOf("sp@exemplo.test");
		tokenTutor = tokenOf("st@exemplo.test");
	}

	private String tokenOf(String email) throws Exception {
		String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(json("email", email, "password", PASSWORD)))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		return "Bearer " + JsonPath.read(body, "$.accessToken");
	}

	private String createService(String body) throws Exception {
		return mvc.perform(post("/api/provider/services").header(HttpHeaders.AUTHORIZATION, tokenOwner).contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
	}

	private UUID petOfSize(String size) {
		return jdbc.queryForObject("INSERT INTO pets (customer_id, name, species, size) VALUES (?, 'Pet', 'DOG', ?) RETURNING id", UUID.class, tutor, size);
	}

	private UUID petWithoutSize() {
		return jdbc.queryForObject("INSERT INTO pets (customer_id, name, species) VALUES (?, 'Pet', 'DOG') RETURNING id", UUID.class, tutor);
	}

	private String book(String serviceId, UUID petId, int daysAhead) throws Exception {
		String start = LocalDate.now(ZoneId.of("America/Sao_Paulo")).plusDays(daysAhead) + "T10:00:00";
		return mvc.perform(post("/api/appointments").header(HttpHeaders.AUTHORIZATION, tokenTutor).contentType(MediaType.APPLICATION_JSON)
				.content("{\"providerId\":\"" + providerId + "\",\"serviceId\":\"" + serviceId + "\",\"petId\":\"" + petId + "\",\"localStart\":\"" + start + "\"}"))
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
	}

	@Test
	void prestadorCadastraEEditaOsPrecosEmCentavos() throws Exception {
		String created = createService("{\"name\":\"Banho\",\"durationMinutes\":60,\"priceCents\":5000,\"priceMediumCents\":7000,\"priceLargeCents\":9000}");
		assertEquals(5000, (int) JsonPath.read(created, "$.priceCents"));
		assertEquals(7000, (int) JsonPath.read(created, "$.priceMediumCents"));
		assertEquals(9000, (int) JsonPath.read(created, "$.priceLargeCents"));
		String id = JsonPath.read(created, "$.id");
		mvc.perform(put("/api/provider/services/" + id).header(HttpHeaders.AUTHORIZATION, tokenOwner).contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\":\"Banho\",\"durationMinutes\":60,\"active\":true,\"priceCents\":5500}")).andExpect(status().isOk())
				.andExpect(jsonPath("$.priceCents").value(5500)).andExpect(jsonPath("$.priceMediumCents").doesNotExist()).andExpect(jsonPath("$.priceLargeCents").doesNotExist());
	}

	@Test
	void semPrecoEhACombinar() throws Exception {
		String created = createService("{\"name\":\"Tosa\",\"durationMinutes\":45}");
		assertNull(JsonPath.read(created, "$.priceCents"));
		assertNull(JsonPath.read(created, "$.priceMediumCents"));
	}

	@Test
	void precoForaDaFaixaEh400PorCampo() throws Exception {
		mvc.perform(post("/api/provider/services").header(HttpHeaders.AUTHORIZATION, tokenOwner).contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\":\"Banho\",\"durationMinutes\":60,\"priceCents\":-1}")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.priceCents").exists());
		mvc.perform(post("/api/provider/services").header(HttpHeaders.AUTHORIZATION, tokenOwner).contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\":\"Banho\",\"durationMinutes\":60,\"priceLargeCents\":10000001}")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.priceLargeCents").exists());
		assertEquals(0, count("SELECT count(*) FROM services"));
	}

	@Test
	void bancoRecusaPrecoForaDaFaixa() throws Exception {
		String id = JsonPath.read(createService("{\"name\":\"Banho\",\"durationMinutes\":60,\"priceCents\":5000}"), "$.id");
		for (String set : new String[] { "price_cents = -1", "price_medium_cents = 10000001", "price_large_cents = -5" }) {
			assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("UPDATE services SET " + set + " WHERE id = ?::uuid", id), set);
		}
		jdbc.update("UPDATE services SET price_cents = 0 WHERE id = ?::uuid", id); // gratuito é um valor válido
	}

	@Test
	void clienteVeOPrecoNoCatalogoAntesDeReservar() throws Exception {
		createService("{\"name\":\"Banho\",\"durationMinutes\":60,\"priceCents\":5000,\"priceLargeCents\":9000}");
		mvc.perform(get("/api/catalog/providers/" + providerId + "/services").header(HttpHeaders.AUTHORIZATION, tokenTutor)).andExpect(status().isOk())
				.andExpect(jsonPath("$[0].priceCents").value(5000)).andExpect(jsonPath("$[0].priceLargeCents").value(9000)).andExpect(jsonPath("$[0].priceMediumCents").doesNotExist());
	}

	@Test
	void reservaCongelaOValorPeloPorteDoPet() throws Exception {
		String service = JsonPath.read(createService("{\"name\":\"Banho\",\"durationMinutes\":60,\"priceCents\":5000,\"priceMediumCents\":7000,\"priceLargeCents\":9000}"), "$.id");
		assertEquals(5000, (int) JsonPath.read(book(service, petOfSize("SMALL"), 3), "$.priceCents"));
		assertEquals(7000, (int) JsonPath.read(book(service, petOfSize("MEDIUM"), 4), "$.priceCents"));
		assertEquals(9000, (int) JsonPath.read(book(service, petOfSize("LARGE"), 5), "$.priceCents"));
		assertEquals(5000, (int) JsonPath.read(book(service, petWithoutSize(), 6), "$.priceCents"), "sem porte usa o valor base");
	}

	@Test
	void porteSemPrecoProprioUsaOBaseESemPrecoNenhumEhNulo() throws Exception {
		String base = JsonPath.read(createService("{\"name\":\"Banho\",\"durationMinutes\":60,\"priceCents\":5000}"), "$.id");
		assertEquals(5000, (int) JsonPath.read(book(base, petOfSize("LARGE"), 3), "$.priceCents"));
		String free = JsonPath.read(createService("{\"name\":\"Tosa\",\"durationMinutes\":60}"), "$.id");
		assertNull(JsonPath.read(book(free, petOfSize("LARGE"), 4), "$.priceCents"));
	}

	@Test
	void mudarOPrecoDepoisNaoReescreveOHistorico() throws Exception {
		String service = JsonPath.read(createService("{\"name\":\"Banho\",\"durationMinutes\":60,\"priceCents\":5000}"), "$.id");
		book(service, petWithoutSize(), 3);
		mvc.perform(put("/api/provider/services/" + service).header(HttpHeaders.AUTHORIZATION, tokenOwner).contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\":\"Banho\",\"durationMinutes\":60,\"active\":true,\"priceCents\":8000}")).andExpect(status().isOk());
		assertEquals(5000, jdbc.queryForObject("SELECT price_cents FROM appointments", Integer.class), "o agendamento mantém o valor da reserva");
		mvc.perform(get("/api/appointments/mine").header(HttpHeaders.AUTHORIZATION, tokenTutor)).andExpect(jsonPath("$[0].priceCents").value(5000));
		book(service, petWithoutSize(), 4);
		assertEquals(8000, jdbc.queryForObject("SELECT price_cents FROM appointments ORDER BY created_at DESC LIMIT 1", Integer.class), "a reserva nova usa o preço novo");
	}

	@Test
	void agendaDoDiaMostraOValorEASemanaNao() throws Exception {
		String service = JsonPath.read(createService("{\"name\":\"Banho\",\"durationMinutes\":60,\"priceCents\":5000}"), "$.id");
		String day = LocalDate.now(ZoneId.of("America/Sao_Paulo")).plusDays(3).toString();
		book(service, petWithoutSize(), 3);
		mvc.perform(get("/api/agenda").param("date", day).header(HttpHeaders.AUTHORIZATION, tokenOwner)).andExpect(status().isOk()).andExpect(jsonPath("$[0].priceCents").value(5000));
		String week = mvc.perform(get("/api/agenda/week").param("start", day).header(HttpHeaders.AUTHORIZATION, tokenOwner)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		assertNull(JsonPath.read(week, "$[0].priceCents"));
	}
}
