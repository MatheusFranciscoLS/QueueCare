package com.queuecare.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Cadastro e reserva pelo balcão (ADR 0033): a equipe cadastra o tutor que liga ou chama no WhatsApp, cadastra o pet e agenda por ele. A equipe só alcança o que o
 * PRÓPRIO estúdio criou (qualquer outro alvo responde 404 igual), a conta nasce sem acesso, e a reserva é a mesma do cliente (mesmas regras, mesma trava do banco),
 * com a equipe registrada como autora.
 */
class CounterIntegrationTest extends AuthIntegrationTestBase {

	UUID ownerA, ownerB, attendantA, attendantB, joao;
	UUID providerA, providerB, serviceA;
	String tokenOwnerA, tokenAttendantA, tokenOwnerB, tokenAttendantB, tokenJoao;

	@BeforeEach
	void seed() throws Exception {
		ownerA = seedUser("ca@exemplo.test", "PROVIDER", true);
		ownerB = seedUser("cb@exemplo.test", "PROVIDER", true);
		attendantA = seedUser("ca1@exemplo.test", "ATTENDANT", true);
		attendantB = seedUser("cb1@exemplo.test", "ATTENDANT", true);
		joao = seedUser("joao-balcao@exemplo.test", "CUSTOMER", true);
		providerA = business(ownerA, "Estúdio A");
		providerB = business(ownerB, "Estúdio B");
		jdbc.update("INSERT INTO provider_staff (provider_id, user_id) VALUES (?, ?)", providerA, attendantA);
		jdbc.update("INSERT INTO provider_staff (provider_id, user_id) VALUES (?, ?)", providerB, attendantB);
		serviceA = jdbc.queryForObject("INSERT INTO services (provider_id, name, duration_minutes) VALUES (?, 'Banho', 60) RETURNING id", UUID.class, providerA);
		for (int day = 1; day <= 7; day++) {
			jdbc.update("INSERT INTO availability_windows (provider_id, day_of_week, start_time, end_time) VALUES (?, ?, '08:00', '18:00')", providerA, day);
		}
		tokenOwnerA = tokenOf("ca@exemplo.test");
		tokenAttendantA = tokenOf("ca1@exemplo.test");
		tokenOwnerB = tokenOf("cb@exemplo.test");
		tokenAttendantB = tokenOf("cb1@exemplo.test");
		tokenJoao = tokenOf("joao-balcao@exemplo.test");
	}

	private UUID business(UUID owner, String name) {
		return jdbc.queryForObject("INSERT INTO providers (user_id, business_name, time_zone, minimum_notice_minutes, cancellation_deadline_minutes) "
				+ "VALUES (?, ?, 'America/Sao_Paulo', 0, 120) RETURNING id", UUID.class, owner, name);
	}

	private String tokenOf(String email) throws Exception {
		String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(json("email", email, "password", PASSWORD)))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		return "Bearer " + JsonPath.read(body, "$.accessToken");
	}

	private ResultActions createCustomer(String bearer, String name, String phone, String email) throws Exception {
		Map<String, String> body = new java.util.LinkedHashMap<>();
		body.put("name", name);
		if (phone != null) {
			body.put("phone", phone);
		}
		if (email != null) {
			body.put("email", email);
		}
		return mvc.perform(post("/api/agenda/customers").header(HttpHeaders.AUTHORIZATION, bearer).contentType(MediaType.APPLICATION_JSON)
				.content(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(body)));
	}

	private UUID newCustomer(String bearer, String name) throws Exception {
		String body = createCustomer(bearer, name, "(11) 91234-5678", null).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		return UUID.fromString(JsonPath.read(body, "$.id"));
	}

	private UUID newPet(String bearer, UUID customer, String name) throws Exception {
		String body = mvc.perform(post("/api/agenda/customers/" + customer + "/pets").header(HttpHeaders.AUTHORIZATION, bearer).contentType(MediaType.APPLICATION_JSON)
				.content(json("name", name, "species", "DOG"))).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		return UUID.fromString(JsonPath.read(body, "$.id"));
	}

	/** O corpo do 404 repete o caminho pedido (que muda de um alvo para outro); o que não pode mudar é o resto. */
	private static String withoutInstance(String problem) {
		return problem.replaceAll("\"instance\":\"[^\"]*\",?", "");
	}

	private String tomorrow() {
		return LocalDate.now(java.time.ZoneId.of("America/Sao_Paulo")).plusDays(2).toString();
	}

	private ResultActions slots(String bearer, UUID customer, UUID pet) throws Exception {
		return mvc.perform(get("/api/agenda/customers/" + customer + "/slots").param("serviceId", serviceA.toString()).param("date", tomorrow())
				.param("petId", pet.toString()).header(HttpHeaders.AUTHORIZATION, bearer));
	}

	private ResultActions book(String bearer, UUID customer, UUID pet, String localStart) throws Exception {
		return mvc.perform(post("/api/agenda/customers/" + customer + "/appointments").header(HttpHeaders.AUTHORIZATION, bearer).contentType(MediaType.APPLICATION_JSON)
				.content(json("serviceId", serviceA.toString(), "localStart", localStart, "petId", pet.toString())));
	}

	// ---------- cadastro ----------

	@Test
	void odonoCadastraOTutorSemEmailELigaAContaAoEstudioSemDarAcesso() throws Exception {
		String body = createCustomer(tokenOwnerA, "  Dona Marta  ", "(11) 91234-5678", null).andExpect(status().isCreated())
				.andExpect(jsonPath("$.name").value("Dona Marta")).andExpect(jsonPath("$.phone").value("5511912345678")).andExpect(jsonPath("$.hasEmail").value(false))
				.andReturn().getResponse().getContentAsString();
		UUID id = UUID.fromString(JsonPath.read(body, "$.id"));

		Map<String, Object> row = jdbc.queryForMap("SELECT role, active, must_change_password, email, created_by_provider_id FROM users WHERE id = ?", id);
		assertEquals("CUSTOMER", row.get("role"));
		assertEquals(true, row.get("active"));
		assertEquals(true, row.get("must_change_password"));
		assertEquals(providerA, row.get("created_by_provider_id"));
		assertTrue(((String) row.get("email")).endsWith("@balcao.invalid"), "sem e-mail informado, o endereço é reservado e não recebe nada");
		assertEquals(1, count("SELECT count(*) FROM audit_log WHERE action = 'CUSTOMER_CREATED_BY_STUDIO' AND actor_user_id = ?", ownerA));
		assertFalse(body.contains("password"), "nenhum dado de credencial na resposta");
	}

	@Test
	void aContaCriadaNaoTemComoEntrar() throws Exception {
		String body = createCustomer(tokenOwnerA, "Dona Marta", "(11) 91234-5678", null).andReturn().getResponse().getContentAsString();
		UUID id = UUID.fromString(JsonPath.read(body, "$.id"));
		String email = jdbc.queryForObject("SELECT email FROM users WHERE id = ?", String.class, id);
		for (String attempt : List.of(PASSWORD, "123456789012", "balcao")) {
			mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(json("email", email, "password", attempt))).andExpect(status().isUnauthorized());
		}
	}

	@Test
	void oAtendenteTambemCadastraComEmailEEmailRepetidoDaConflitoUniforme() throws Exception {
		createCustomer(tokenAttendantA, "Seu Zé", "(21) 3333-4444", "ze@exemplo.test").andExpect(status().isCreated()).andExpect(jsonPath("$.hasEmail").value(true));
		assertEquals("ze@exemplo.test", jdbc.queryForObject("SELECT email FROM users WHERE name = 'Seu Zé'", String.class));
		// e-mail que já tem conta (do balcão, de tutor que se cadastrou sozinho ou de qualquer perfil): 409 igual, sem dizer de quem
		String first = createCustomer(tokenAttendantA, "Outro", "(21) 3333-4444", "ZE@exemplo.test").andExpect(status().isConflict()).andReturn().getResponse().getContentAsString();
		String second = createCustomer(tokenOwnerA, "Outro", "(21) 3333-4444", "joao-balcao@exemplo.test").andExpect(status().isConflict()).andReturn().getResponse().getContentAsString();
		String third = createCustomer(tokenOwnerA, "Outro", "(21) 3333-4444", "ca@exemplo.test").andExpect(status().isConflict()).andReturn().getResponse().getContentAsString();
		assertEquals(first, second);
		assertEquals(first, third);
	}

	@Test
	void validaNomeTelefoneEEmail() throws Exception {
		createCustomer(tokenOwnerA, "", "(11) 91234-5678", null).andExpect(status().isBadRequest());
		createCustomer(tokenOwnerA, "Ana", null, null).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.phone").exists());
		createCustomer(tokenOwnerA, "Ana", "123", null).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.phone").exists());
		createCustomer(tokenOwnerA, "Ana", "(11) 91234-5678", "isso-nao-e-email").andExpect(status().isBadRequest());
		assertEquals(0, count("SELECT count(*) FROM users WHERE created_by_provider_id IS NOT NULL"));
	}

	@Test
	void soEquipeCadastra() throws Exception {
		createCustomer(tokenJoao, "Ana", "(11) 91234-5678", null).andExpect(status().isForbidden());
		mvc.perform(post("/api/agenda/customers").contentType(MediaType.APPLICATION_JSON).content(json("name", "Ana", "phone", "(11) 91234-5678"))).andExpect(status().isUnauthorized());
	}

	@Test
	void oCadastroAparecaNaListaDoEstudioMesmoSemAgendamentoENuncaNaDeOutro() throws Exception {
		UUID marta = newCustomer(tokenOwnerA, "Dona Marta");
		newPet(tokenOwnerA, marta, "Bidu");
		mvc.perform(get("/api/agenda/customers").header(HttpHeaders.AUTHORIZATION, tokenAttendantA)).andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].id").value(marta.toString())).andExpect(jsonPath("$[0].byCounter").value(true))
				.andExpect(jsonPath("$[0].hasEmail").value(false)).andExpect(jsonPath("$[0].visits").value(0)).andExpect(jsonPath("$[0].pets[0]").value("Bidu"));
		mvc.perform(get("/api/agenda/customers").param("q", "bidu").header(HttpHeaders.AUTHORIZATION, tokenOwnerA)).andExpect(jsonPath("$.length()").value(1));
		mvc.perform(get("/api/agenda/customers").param("q", "zzz").header(HttpHeaders.AUTHORIZATION, tokenOwnerA)).andExpect(jsonPath("$.length()").value(0));
		mvc.perform(get("/api/agenda/customers").header(HttpHeaders.AUTHORIZATION, tokenOwnerB)).andExpect(jsonPath("$.length()").value(0));
	}

	// ---------- pets ----------

	@Test
	void aEquipeCadastraEListaOsPetsDoTutorDoBalcao() throws Exception {
		UUID marta = newCustomer(tokenOwnerA, "Dona Marta");
		newPet(tokenAttendantA, marta, "Bidu");
		newPet(tokenOwnerA, marta, "Alfa");
		mvc.perform(get("/api/agenda/customers/" + marta + "/pets").header(HttpHeaders.AUTHORIZATION, tokenOwnerA)).andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(2)).andExpect(jsonPath("$[0].name").value("Alfa"));
		assertEquals(2, count("SELECT count(*) FROM pets WHERE customer_id = ?", marta));
	}

	@Test
	void naoHaComoAgirPorTutorQueNaoESeuTodosOsAlvosInvalidosTemAMesmaResposta() throws Exception {
		UUID marta = newCustomer(tokenOwnerA, "Dona Marta");
		UUID petMarta = newPet(tokenOwnerA, marta, "Bidu");
		UUID doOutroEstudio = newCustomer(tokenOwnerB, "Cliente do B");
		String reference = mvc.perform(get("/api/agenda/customers/" + UUID.randomUUID() + "/pets").header(HttpHeaders.AUTHORIZATION, tokenOwnerA))
				.andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();
		// tutor que se cadastrou sozinho, tutor do balcão de OUTRO estúdio e a própria equipe: o mesmo 404
		for (UUID target : List.of(joao, doOutroEstudio, ownerA)) {
			assertEquals(withoutInstance(reference), withoutInstance(mvc.perform(get("/api/agenda/customers/" + target + "/pets").header(HttpHeaders.AUTHORIZATION, tokenOwnerA)).andExpect(status().isNotFound())
					.andReturn().getResponse().getContentAsString()));
			mvc.perform(post("/api/agenda/customers/" + target + "/pets").header(HttpHeaders.AUTHORIZATION, tokenOwnerA).contentType(MediaType.APPLICATION_JSON)
					.content(json("name", "Intruso", "species", "DOG"))).andExpect(status().isNotFound());
			slots(tokenOwnerA, target, petMarta).andExpect(status().isNotFound());
			book(tokenOwnerA, target, petMarta, tomorrow() + "T10:00:00").andExpect(status().isNotFound());
		}
		// e a equipe do outro estúdio não alcança a Marta
		mvc.perform(get("/api/agenda/customers/" + marta + "/pets").header(HttpHeaders.AUTHORIZATION, tokenAttendantB)).andExpect(status().isNotFound());
		assertEquals(0, count("SELECT count(*) FROM pets WHERE name = 'Intruso'"));
		assertEquals(0, count("SELECT count(*) FROM appointments"));
	}

	@Test
	void tutorNaoUsaAsRotasDaEquipe() throws Exception {
		UUID marta = newCustomer(tokenOwnerA, "Dona Marta");
		mvc.perform(get("/api/agenda/customers/" + marta + "/pets").header(HttpHeaders.AUTHORIZATION, tokenJoao)).andExpect(status().isForbidden());
		mvc.perform(get("/api/agenda/customers/" + marta + "/pets")).andExpect(status().isUnauthorized());
	}

	// ---------- reserva ----------

	@Test
	void aEquipeVeOsHorariosEAgendaPeloTutorERegistraSeComoAutora() throws Exception {
		UUID marta = newCustomer(tokenOwnerA, "Dona Marta");
		UUID bidu = newPet(tokenOwnerA, marta, "Bidu");
		String free = slots(tokenAttendantA, marta, bidu).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(org.hamcrest.Matchers.greaterThan(0)))
				.andReturn().getResponse().getContentAsString();
		String start = JsonPath.read(free, "$[0].localStart");

		String created = book(tokenAttendantA, marta, bidu, start).andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("SCHEDULED"))
				.andReturn().getResponse().getContentAsString();
		UUID appointment = UUID.fromString(JsonPath.read(created, "$.id"));
		assertEquals(providerA, jdbc.queryForObject("SELECT provider_id FROM appointments WHERE id = ?", UUID.class, appointment));
		assertEquals(marta, jdbc.queryForObject("SELECT customer_id FROM appointments WHERE id = ?", UUID.class, appointment));
		assertEquals("Bidu", jdbc.queryForObject("SELECT pet_name FROM appointments WHERE id = ?", String.class, appointment));
		assertEquals(attendantA, jdbc.queryForObject("SELECT actor_id FROM appointment_events WHERE appointment_id = ? AND event_type = 'SCHEDULED'", UUID.class, appointment),
				"a equipe, e não o tutor, é a autora da reserva");

		// o horário ocupado some da lista e uma segunda reserva no mesmo horário é conflito (a mesma trava do banco do cliente)
		String after = slots(tokenOwnerA, marta, bidu).andReturn().getResponse().getContentAsString();
		assertFalse(((List<String>) JsonPath.read(after, "$[*].localStart")).contains(start));
		UUID outro = newCustomer(tokenOwnerA, "Seu Zé");
		UUID rex = newPet(tokenOwnerA, outro, "Rex");
		book(tokenOwnerA, outro, rex, start).andExpect(status().isConflict());
	}

	@Test
	void aReservaPeloBalcaoSegueAsMesmasRegrasDoCliente() throws Exception {
		UUID marta = newCustomer(tokenOwnerA, "Dona Marta");
		UUID bidu = newPet(tokenOwnerA, marta, "Bidu");
		book(tokenOwnerA, marta, bidu, tomorrow() + "T03:00:00").andExpect(status().isUnprocessableEntity()); // fora do expediente
		mvc.perform(post("/api/agenda/customers/" + marta + "/appointments").header(HttpHeaders.AUTHORIZATION, tokenOwnerA).contentType(MediaType.APPLICATION_JSON)
				.content(json("serviceId", serviceA.toString(), "localStart", tomorrow() + "T10:00:00"))).andExpect(status().isBadRequest()); // sem pet
		UUID petDoJoao = jdbc.queryForObject("INSERT INTO pets (customer_id, name, species) VALUES (?, 'Thor', 'DOG') RETURNING id", UUID.class, joao);
		book(tokenOwnerA, marta, petDoJoao, tomorrow() + "T10:00:00").andExpect(status().isNotFound()); // pet de outro tutor: igual a pet inexistente
		assertEquals(0, count("SELECT count(*) FROM appointments"));
	}
}
