package com.queuecare.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

/**
 * Os direitos de privacidade de quem foi cadastrado pelo balcão (ADR 0033, riscos): sem acesso ao app, o tutor não baixa nem exclui os próprios dados sozinho, então o
 * DONO atende o pedido por ele. Só o dono, só de conta que o próprio estúdio criou, e a exclusão só vale enquanto o tutor nunca assumiu a conta.
 */
class CounterPrivacyIntegrationTest extends AuthIntegrationTestBase {

	UUID ownerA, ownerB, attendantA, joao, marta;
	UUID providerA, providerB, serviceA;
	String tokenOwnerA, tokenOwnerB, tokenAttendantA, tokenJoao;

	@BeforeEach
	void seed() throws Exception {
		ownerA = seedUser("pa@exemplo.test", "PROVIDER", true);
		ownerB = seedUser("pb@exemplo.test", "PROVIDER", true);
		attendantA = seedUser("pa1@exemplo.test", "ATTENDANT", true);
		joao = seedUser("joao-priv@exemplo.test", "CUSTOMER", true);
		providerA = business(ownerA, "Estúdio A");
		providerB = business(ownerB, "Estúdio B");
		jdbc.update("INSERT INTO provider_staff (provider_id, user_id) VALUES (?, ?)", providerA, attendantA);
		serviceA = jdbc.queryForObject("INSERT INTO services (provider_id, name, duration_minutes) VALUES (?, 'Banho', 60) RETURNING id", UUID.class, providerA);
		tokenOwnerA = tokenOf("pa@exemplo.test");
		tokenOwnerB = tokenOf("pb@exemplo.test");
		tokenAttendantA = tokenOf("pa1@exemplo.test");
		tokenJoao = tokenOf("joao-priv@exemplo.test");

		String body = mvc.perform(post("/api/agenda/customers").header(HttpHeaders.AUTHORIZATION, tokenOwnerA).contentType(MediaType.APPLICATION_JSON)
				.content(json("name", "Dona Marta", "phone", "(11) 91234-5678"))).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		marta = UUID.fromString(JsonPath.read(body, "$.id"));
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

	/** Um pet e um agendamento FUTURO marcado da Marta no estúdio A, direto no banco. */
	private UUID petAndFutureAppointment() {
		UUID pet = jdbc.queryForObject("INSERT INTO pets (customer_id, name, species, breed) VALUES (?, 'Bidu', 'DOG', 'Vira-lata') RETURNING id", UUID.class, marta);
		OffsetDateTime start = OffsetDateTime.now().plusDays(3).withMinute(0).withSecond(0).withNano(0);
		return jdbc.queryForObject("INSERT INTO appointments (provider_id, service_id, customer_id, starts_at, ends_at, status, pet_id, pet_name, pet_species) "
				+ "VALUES (?, ?, ?, ?, ?, 'SCHEDULED', ?, 'Bidu', 'DOG') RETURNING id", UUID.class, providerA, serviceA, marta, start, start.plusMinutes(60), pet);
	}

	// ---------- exportar ----------

	@Test
	void odonoBaixaOsDadosDoTutorDoBalcaoSemCacheEComAuditoria() throws Exception {
		petAndFutureAppointment();
		String body = mvc.perform(get("/api/provider/customers/" + marta + "/export").header(HttpHeaders.AUTHORIZATION, tokenOwnerA)).andExpect(status().isOk())
				.andExpect(header().string(HttpHeaders.CACHE_CONTROL, org.hamcrest.Matchers.containsString("no-store")))
				.andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, org.hamcrest.Matchers.containsString("dados-do-tutor.json")))
				.andExpect(jsonPath("$.account.name").value("Dona Marta")).andExpect(jsonPath("$.account.phone").value("5511912345678"))
				.andExpect(jsonPath("$.pets[0].name").value("Bidu")).andExpect(jsonPath("$.appointments[0].studio").value("Estúdio A"))
				.andReturn().getResponse().getContentAsString();
		assertFalse(body.contains("password"), "nenhum dado de credencial");
		assertEquals(1, count("SELECT count(*) FROM audit_log WHERE action = 'CUSTOMER_EXPORTED_BY_STUDIO' AND actor_user_id = ? AND target_user_id = ?", ownerA, marta));
	}

	@Test
	void soODonoExportaEOAtendenteOTutorEOutroEstudioNao() throws Exception {
		mvc.perform(get("/api/provider/customers/" + marta + "/export").header(HttpHeaders.AUTHORIZATION, tokenAttendantA)).andExpect(status().isForbidden());
		mvc.perform(get("/api/provider/customers/" + marta + "/export").header(HttpHeaders.AUTHORIZATION, tokenJoao)).andExpect(status().isForbidden());
		mvc.perform(get("/api/provider/customers/" + marta + "/export")).andExpect(status().isUnauthorized());
		mvc.perform(get("/api/provider/customers/" + marta + "/export").header(HttpHeaders.AUTHORIZATION, tokenOwnerB)).andExpect(status().isNotFound());
		assertEquals(0, count("SELECT count(*) FROM audit_log WHERE action = 'CUSTOMER_EXPORTED_BY_STUDIO'"));
	}

	@Test
	void naoExportaContaQueOTutorCriouSozinhoNemIdInexistente() throws Exception {
		mvc.perform(get("/api/provider/customers/" + joao + "/export").header(HttpHeaders.AUTHORIZATION, tokenOwnerA)).andExpect(status().isNotFound());
		mvc.perform(get("/api/provider/customers/" + UUID.randomUUID() + "/export").header(HttpHeaders.AUTHORIZATION, tokenOwnerA)).andExpect(status().isNotFound());
		assertEquals(0, count("SELECT count(*) FROM audit_log WHERE action = 'CUSTOMER_EXPORTED_BY_STUDIO'"));
	}

	// ---------- excluir (anonimizar) ----------

	@Test
	void odonoExcluiOCadastroEFazOMesmoQueOTutorFariaComAuditoria() throws Exception {
		UUID appointment = petAndFutureAppointment();
		mvc.perform(post("/api/provider/customers/" + marta + "/anonymize").header(HttpHeaders.AUTHORIZATION, tokenOwnerA)).andExpect(status().isNoContent());

		Map<String, Object> row = jdbc.queryForMap("SELECT name, email, phone, active, anonymized_at FROM users WHERE id = ?", marta);
		assertEquals("Conta removida", row.get("name"));
		assertTrue(((String) row.get("email")).endsWith("@removida.invalid"));
		assertEquals(null, row.get("phone"));
		assertEquals(false, row.get("active"));
		assertTrue(row.get("anonymized_at") != null);
		assertEquals("Pet removido", jdbc.queryForObject("SELECT name FROM pets WHERE customer_id = ?", String.class, marta));
		assertEquals("CANCELLED", jdbc.queryForObject("SELECT status FROM appointments WHERE id = ?", String.class, appointment), "o horário marcado volta para o estúdio");
		assertEquals(ownerA, jdbc.queryForObject("SELECT actor_id FROM appointment_events WHERE appointment_id = ? AND event_type = 'CANCELLED'", UUID.class, appointment));
		assertEquals(1, count("SELECT count(*) FROM audit_log WHERE action = 'CUSTOMER_ANONYMIZED_BY_STUDIO' AND actor_user_id = ? AND target_user_id = ?", ownerA, marta));

		// já excluída: o mesmo 404 de qualquer alvo inválido, e nada se repete
		mvc.perform(post("/api/provider/customers/" + marta + "/anonymize").header(HttpHeaders.AUTHORIZATION, tokenOwnerA)).andExpect(status().isNotFound());
		assertEquals(1, count("SELECT count(*) FROM audit_log WHERE action = 'CUSTOMER_ANONYMIZED_BY_STUDIO'"));
	}

	@Test
	void naoExcluiContaDeQuemJaAssumiuOAcessoENadaMuda() throws Exception {
		petAndFutureAppointment();
		jdbc.update("UPDATE users SET must_change_password = false WHERE id = ?", marta); // o tutor entrou e criou a própria senha
		mvc.perform(post("/api/provider/customers/" + marta + "/anonymize").header(HttpHeaders.AUTHORIZATION, tokenOwnerA)).andExpect(status().isConflict())
				.andExpect(jsonPath("$.reason").value("CUSTOMER_HAS_ACCESS"));
		assertEquals("Dona Marta", jdbc.queryForObject("SELECT name FROM users WHERE id = ?", String.class, marta));
		assertEquals(true, jdbc.queryForObject("SELECT active FROM users WHERE id = ?", Boolean.class, marta));
		assertEquals(0, count("SELECT count(*) FROM audit_log WHERE action = 'CUSTOMER_ANONYMIZED_BY_STUDIO'"));
	}

	@Test
	void soODonoExcluiEOutroEstudioETutorQueSeCadastrouSozinhoNaoSaoAlcancados() throws Exception {
		mvc.perform(post("/api/provider/customers/" + marta + "/anonymize").header(HttpHeaders.AUTHORIZATION, tokenAttendantA)).andExpect(status().isForbidden());
		mvc.perform(post("/api/provider/customers/" + marta + "/anonymize").header(HttpHeaders.AUTHORIZATION, tokenJoao)).andExpect(status().isForbidden());
		mvc.perform(post("/api/provider/customers/" + marta + "/anonymize").header(HttpHeaders.AUTHORIZATION, tokenOwnerB)).andExpect(status().isNotFound());
		mvc.perform(post("/api/provider/customers/" + joao + "/anonymize").header(HttpHeaders.AUTHORIZATION, tokenOwnerA)).andExpect(status().isNotFound());
		assertEquals("Dona Marta", jdbc.queryForObject("SELECT name FROM users WHERE id = ?", String.class, marta));
		assertEquals("joao-priv@exemplo.test", jdbc.queryForObject("SELECT email FROM users WHERE id = ?", String.class, joao));
	}
}
