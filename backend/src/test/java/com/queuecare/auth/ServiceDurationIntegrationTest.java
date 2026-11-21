package com.queuecare.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
 * Duração por porte e por pelo (ADR 0020): o estúdio cadastra a duração de cada porte e um extra para pelo longo, cacheado ou duplo; a consulta de
 * horários e a reserva usam a duração do PET (o mesmo cálculo, então horário oferecido e aceito não divergem); o fim do agendamento fica
 * congelado; e o banco recusa o que está fora da faixa.
 */
class ServiceDurationIntegrationTest extends AuthIntegrationTestBase {

	UUID owner, tutor, other, providerId;
	String tokenOwner, tokenTutor, tokenOther;

	@BeforeEach
	void seed() throws Exception {
		owner = seedUser("du@exemplo.test", "PROVIDER", true);
		tutor = seedUser("dt@exemplo.test", "CUSTOMER", true);
		other = seedUser("do@exemplo.test", "CUSTOMER", true);
		providerId = jdbc.queryForObject("INSERT INTO providers (user_id, business_name, time_zone, minimum_notice_minutes, cancellation_deadline_minutes) "
				+ "VALUES (?, 'Negócio', 'America/Sao_Paulo', 0, 120) RETURNING id", UUID.class, owner);
		jdbc.update("INSERT INTO availability_windows (provider_id, day_of_week, start_time, end_time) SELECT ?, d, '08:00', '12:00' FROM generate_series(1, 7) d", providerId);
		tokenOwner = tokenOf("du@exemplo.test");
		tokenTutor = tokenOf("dt@exemplo.test");
		tokenOther = tokenOf("do@exemplo.test");
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

	private UUID pet(UUID owner, String size, String coat) {
		return jdbc.queryForObject("INSERT INTO pets (customer_id, name, species, size, coat) VALUES (?, 'Pet', 'DOG', ?, ?) RETURNING id", UUID.class, owner, size, coat);
	}

	private LocalDate day(int daysAhead) {
		return LocalDate.now(ZoneId.of("America/Sao_Paulo")).plusDays(daysAhead);
	}

	private String book(String serviceId, UUID petId, LocalDate date, String time) throws Exception {
		return mvc.perform(post("/api/appointments").header(HttpHeaders.AUTHORIZATION, tokenTutor).contentType(MediaType.APPLICATION_JSON)
				.content("{\"providerId\":\"" + providerId + "\",\"serviceId\":\"" + serviceId + "\",\"petId\":\"" + petId + "\",\"localStart\":\"" + date + "T" + time + ":00\"}"))
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
	}

	private String slots(String serviceId, LocalDate date, UUID petId, String token) throws Exception {
		String pet = petId == null ? "" : "&petId=" + petId;
		return mvc.perform(get("/api/providers/" + providerId + "/slots?serviceId=" + serviceId + "&date=" + date + pet).header(HttpHeaders.AUTHORIZATION, token))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
	}

	private static long minutesOf(String appointmentJson) {
		return Duration.between(Instant.parse(JsonPath.read(appointmentJson, "$.startsAt")), Instant.parse(JsonPath.read(appointmentJson, "$.endsAt"))).toMinutes();
	}

	private static final String SERVICE = "{\"name\":\"Banho\",\"durationMinutes\":60,\"durationMediumMinutes\":90,\"durationLargeMinutes\":120,\"coatExtraMinutes\":30}";

	@Test
	void prestadorCadastraEEditaAsDuracoes() throws Exception {
		String created = createService(SERVICE);
		assertEquals(90, (int) JsonPath.read(created, "$.durationMediumMinutes"));
		assertEquals(120, (int) JsonPath.read(created, "$.durationLargeMinutes"));
		assertEquals(30, (int) JsonPath.read(created, "$.coatExtraMinutes"));
		String id = JsonPath.read(created, "$.id");
		mvc.perform(put("/api/provider/services/" + id).header(HttpHeaders.AUTHORIZATION, tokenOwner).contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\":\"Banho\",\"durationMinutes\":60,\"active\":true}")).andExpect(status().isOk())
				.andExpect(jsonPath("$.durationMediumMinutes").doesNotExist()).andExpect(jsonPath("$.durationLargeMinutes").doesNotExist())
				.andExpect(jsonPath("$.coatExtraMinutes").value(0));
	}

	@Test
	void semOsCamposNovosADuracaoEhABaseEOExtraEhZero() throws Exception {
		String created = createService("{\"name\":\"Tosa\",\"durationMinutes\":45}");
		assertEquals(0, (int) JsonPath.read(created, "$.coatExtraMinutes"));
		mvc.perform(get("/api/catalog/providers/" + providerId + "/services").header(HttpHeaders.AUTHORIZATION, tokenTutor)).andExpect(status().isOk())
				.andExpect(jsonPath("$[0].durationMinutes").value(45)).andExpect(jsonPath("$[0].durationMediumMinutes").doesNotExist())
				.andExpect(jsonPath("$[0].coatExtraMinutes").value(0));
	}

	@Test
	void duracaoForaDaFaixaEh400PorCampo() throws Exception {
		for (String[] c : new String[][] { { "durationMediumMinutes", "4" }, { "durationLargeMinutes", "481" }, { "coatExtraMinutes", "-1" }, { "coatExtraMinutes", "241" } }) {
			mvc.perform(post("/api/provider/services").header(HttpHeaders.AUTHORIZATION, tokenOwner).contentType(MediaType.APPLICATION_JSON)
					.content("{\"name\":\"Banho\",\"durationMinutes\":60,\"" + c[0] + "\":" + c[1] + "}")).andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.errors." + c[0]).exists());
		}
		// O pior caso (a maior duração de porte + o extra) também não pode passar de 480 minutos.
		mvc.perform(post("/api/provider/services").header(HttpHeaders.AUTHORIZATION, tokenOwner).contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\":\"Banho\",\"durationMinutes\":60,\"durationLargeMinutes\":400,\"coatExtraMinutes\":100}")).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.coatExtraMinutes").exists());
		assertEquals(0, count("SELECT count(*) FROM services"));
	}

	@Test
	void bancoRecusaDuracaoForaDaFaixa() throws Exception {
		String id = JsonPath.read(createService("{\"name\":\"Banho\",\"durationMinutes\":60}"), "$.id");
		for (String set : new String[] { "duration_medium_minutes = 4", "duration_large_minutes = 481", "coat_extra_minutes = -1", "coat_extra_minutes = 241" }) {
			assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("UPDATE services SET " + set + " WHERE id = ?::uuid", id), set);
		}
	}

	@Test
	void clienteVeAsDuracoesNoCatalogo() throws Exception {
		createService(SERVICE);
		mvc.perform(get("/api/catalog/providers/" + providerId + "/services").header(HttpHeaders.AUTHORIZATION, tokenTutor)).andExpect(status().isOk())
				.andExpect(jsonPath("$[0].durationMediumMinutes").value(90)).andExpect(jsonPath("$[0].durationLargeMinutes").value(120))
				.andExpect(jsonPath("$[0].coatExtraMinutes").value(30));
	}

	@Test
	void reservaUsaADuracaoDoPorteEDoPelo() throws Exception {
		String service = JsonPath.read(createService(SERVICE), "$.id");
		assertEquals(60, minutesOf(book(service, pet(tutor, "SMALL", "SHORT"), day(3), "08:00")), "pequeno, pelo curto: base");
		assertEquals(90, minutesOf(book(service, pet(tutor, "MEDIUM", "SHORT"), day(4), "08:00")), "médio: a do porte");
		assertEquals(120, minutesOf(book(service, pet(tutor, "LARGE", "MEDIUM"), day(5), "08:00")), "grande: a do porte; pelo médio não soma");
		assertEquals(90, minutesOf(book(service, pet(tutor, "SMALL", "CURLY"), day(6), "08:00")), "pequeno de pelo cacheado: base + extra");
		assertEquals(120, minutesOf(book(service, pet(tutor, "MEDIUM", "DOUBLE"), day(7), "08:00")), "médio de pelo duplo: porte + extra");
		assertEquals(150, minutesOf(book(service, pet(tutor, "LARGE", "LONG"), day(8), "08:00")), "grande de pelo longo: porte + extra");
		UUID bare = jdbc.queryForObject("INSERT INTO pets (customer_id, name, species) VALUES (?, 'Pet', 'DOG') RETURNING id", UUID.class, tutor);
		assertEquals(60, minutesOf(book(service, bare, day(9), "08:00")), "sem porte e sem pelo: base");
	}

	@Test
	void horariosOferecidosSaoOsQueaReservaAceita() throws Exception {
		String service = JsonPath.read(createService(SERVICE), "$.id");
		UUID small = pet(tutor, "SMALL", "SHORT");
		UUID bigCurly = pet(tutor, "LARGE", "CURLY"); // 120 + 30 = 150 min, expediente de 8h às 12h (240 min)
		LocalDate date = day(3);
		String forSmall = slots(service, date, small, tokenTutor);
		assertEquals(13, ((java.util.List<?>) JsonPath.read(forSmall, "$")).size(), "60 min em 4 h, a cada 15 min: das 8h às 11h (13 horários)");
		String forBig = slots(service, date, bigCurly, tokenTutor);
		assertEquals(7, ((java.util.List<?>) JsonPath.read(forBig, "$")).size(), "150 min: o último começa às 9h30 (7 horários)");
		assertEquals(date + "T09:30:00", JsonPath.read(forBig, "$[6].localStart"));
		// O último horário oferecido para o pet grande é aceito; o horário seguinte (que passaria do expediente) é recusado.
		book(service, bigCurly, date, "09:30");
		mvc.perform(post("/api/appointments").header(HttpHeaders.AUTHORIZATION, tokenTutor).contentType(MediaType.APPLICATION_JSON)
				.content("{\"providerId\":\"" + providerId + "\",\"serviceId\":\"" + service + "\",\"petId\":\"" + bigCurly + "\",\"localStart\":\"" + day(4) + "T09:45:00\"}"))
				.andExpect(status().isUnprocessableEntity());
	}

	@Test
	void semPetOuComPetAlheioArquivadoOuInexistenteValeABase() throws Exception {
		String service = JsonPath.read(createService(SERVICE), "$.id");
		UUID othersBig = pet(other, "LARGE", "CURLY");
		UUID archived = pet(tutor, "LARGE", "CURLY");
		jdbc.update("UPDATE pets SET active = false WHERE id = ?", archived);
		LocalDate date = day(3);
		int base = ((java.util.List<?>) JsonPath.read(slots(service, date, null, tokenTutor), "$")).size();
		assertEquals(13, base);
		for (UUID unknown : new UUID[] { othersBig, archived, UUID.randomUUID() }) {
			assertEquals(base, ((java.util.List<?>) JsonPath.read(slots(service, date, unknown, tokenTutor), "$")).size(), "pet alheio, arquivado ou inexistente: ignorado, sem erro");
		}
	}

	@Test
	void editarOServicoDepoisNaoMudaOFimDoAgendamentoMarcado() throws Exception {
		String service = JsonPath.read(createService(SERVICE), "$.id");
		UUID big = pet(tutor, "LARGE", "SHORT");
		book(service, big, day(3), "08:00");
		mvc.perform(put("/api/provider/services/" + service).header(HttpHeaders.AUTHORIZATION, tokenOwner).contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\":\"Banho\",\"durationMinutes\":60,\"durationLargeMinutes\":30,\"active\":true}")).andExpect(status().isOk());
		assertEquals(120, jdbc.queryForObject("SELECT (extract(epoch FROM ends_at - starts_at) / 60)::int FROM appointments", Integer.class), "o atendimento marcado mantém a duração da reserva");
		assertEquals(30, minutesOf(book(service, big, day(4), "08:00")), "a reserva nova usa a duração nova");
	}
}
