package com.queuecare.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/**
 * LGPD mínima (ADR 0027): o tutor registra que leu a política, exporta os próprios dados e exclui a conta, que é anonimizada no lugar. O que é do estúdio
 * (horário, serviço, valor) fica; o que identifica a pessoa e o pet sai; os horários futuros voltam ao estúdio; ninguém mexe na conta de outro.
 */
class PrivacyIntegrationTest extends AuthIntegrationTestBase {

	UUID owner, tutor, other;
	UUID provider, service, petTutor, petOther;
	String tokenOwner, tokenTutor, tokenOther;

	@BeforeEach
	void seed() throws Exception {
		owner = seedUser("pv-owner@exemplo.test", "PROVIDER", true);
		tutor = seedUser("pv-tutor@exemplo.test", "CUSTOMER", true);
		other = seedUser("pv-other@exemplo.test", "CUSTOMER", true);
		jdbc.update("UPDATE users SET name = 'Joana Lima', phone = '5511987654321' WHERE id = ?", tutor);
		provider = jdbc.queryForObject("INSERT INTO providers (user_id, business_name, time_zone, minimum_notice_minutes, cancellation_deadline_minutes) "
				+ "VALUES (?, 'Estúdio Bem-te-vi', 'America/Sao_Paulo', 0, 120) RETURNING id", UUID.class, owner);
		service = jdbc.queryForObject("INSERT INTO services (provider_id, name, duration_minutes, price_cents) VALUES (?, 'Banho', 60, 5000) RETURNING id", UUID.class, provider);
		petTutor = jdbc.queryForObject("INSERT INTO pets (customer_id, name, species, breed, notes, health_notes) VALUES (?, 'Thor', 'DOG', 'Golden', 'Tem medo de secador', 'Alergia a shampoo') RETURNING id",
				UUID.class, tutor);
		petOther = jdbc.queryForObject("INSERT INTO pets (customer_id, name, species, notes) VALUES (?, 'Rex', 'DOG', 'Observação alheia') RETURNING id", UUID.class, other);
		tokenOwner = tokenOf("pv-owner@exemplo.test");
		tokenTutor = tokenOf("pv-tutor@exemplo.test");
		tokenOther = tokenOf("pv-other@exemplo.test");
	}

	private String tokenOf(String email) throws Exception {
		String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(json("email", email, "password", PASSWORD)))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		return "Bearer " + JsonPath.read(body, "$.accessToken");
	}

	private String appointment(UUID customer, UUID pet, String petName, String statusName, int daysAhead, int hour) {
		OffsetDateTime start = LocalDate.now().plusDays(daysAhead).atTime(hour, 0).atZone(java.time.ZoneId.of("America/Sao_Paulo")).toOffsetDateTime();
		return jdbc.queryForObject("INSERT INTO appointments (provider_id, service_id, customer_id, starts_at, ends_at, status, price_cents, pet_id, pet_name, pet_species, pet_breed, pet_notes, pet_health_notes) "
				+ "VALUES (?, ?, ?, ?, ?, ?, 5000, ?, ?, 'DOG', 'Golden', 'Tem medo de secador', 'Alergia a shampoo') RETURNING id::text", String.class, provider, service, customer, start,
				start.plusMinutes(60), statusName, pet, petName);
	}

	private ResultActions delete(String bearer, String password) throws Exception {
		return mvc.perform(post("/api/me/delete-account").header(HttpHeaders.AUTHORIZATION, bearer).contentType(MediaType.APPLICATION_JSON).content(json("password", password)));
	}

	// ---------- aceite da política ----------

	@Test
	void cadastroRegistraAVersaoLidaEORejeitaFormatoInvalido() throws Exception {
		mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\":\"Ana\",\"email\":\"ana-pv@exemplo.test\",\"password\":\"" + PASSWORD + "\",\"privacyPolicyVersion\":\"2026-11-21\"}")).andExpect(status().isCreated());
		Map<String, Object> row = jdbc.queryForMap("SELECT privacy_policy_version, privacy_accepted_at FROM users WHERE email = 'ana-pv@exemplo.test'");
		assertEquals("2026-11-21", row.get("privacy_policy_version"));
		assertNotNull(row.get("privacy_accepted_at"));

		mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\":\"Bia\",\"email\":\"bia-pv@exemplo.test\",\"password\":\"" + PASSWORD + "\",\"privacyPolicyVersion\":\"ontem\"}")).andExpect(status().isBadRequest());
		assertEquals(0, count("SELECT count(*) FROM users WHERE email = 'bia-pv@exemplo.test'"));

		// sem o campo (cadastro por API antiga): continua valendo, sem registro
		mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(json("name", "Caio", "email", "caio-pv@exemplo.test", "password", PASSWORD)))
				.andExpect(status().isCreated());
		assertNull(jdbc.queryForObject("SELECT privacy_policy_version FROM users WHERE email = 'caio-pv@exemplo.test'", String.class));
	}

	// ---------- exportar ----------

	@Test
	void exportaTudoDoTutorEMaisNadaDeNinguemNemCredenciais() throws Exception {
		appointment(tutor, petTutor, "Thor", "COMPLETED", -10, 9);
		appointment(other, petOther, "Rex", "COMPLETED", -9, 9);

		String body = mvc.perform(get("/api/me/export").header(HttpHeaders.AUTHORIZATION, tokenTutor)).andExpect(status().isOk())
				.andExpect(header().string(HttpHeaders.CACHE_CONTROL, org.hamcrest.Matchers.containsString("no-store")))
				.andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, org.hamcrest.Matchers.containsString("meus-dados.json"))).andReturn().getResponse().getContentAsString();

		assertEquals("Joana Lima", JsonPath.read(body, "$.account.name"));
		assertEquals("pv-tutor@exemplo.test", JsonPath.read(body, "$.account.email"));
		assertEquals("5511987654321", JsonPath.read(body, "$.account.phone"));
		assertEquals(List.of("Thor"), JsonPath.read(body, "$.pets[*].name"));
		assertEquals("Alergia a shampoo", JsonPath.read(body, "$.pets[0].healthNotes"));
		assertEquals(1, ((List<?>) JsonPath.read(body, "$.appointments")).size(), "só o agendamento do próprio tutor");
		assertEquals("Estúdio Bem-te-vi", JsonPath.read(body, "$.appointments[0].studio"));
		assertEquals("Banho", JsonPath.read(body, "$.appointments[0].service"));
		assertFalse(body.contains("Rex"), "nada de outro tutor");
		assertFalse(body.contains("password"), "nenhuma credencial");
		assertFalse(body.contains("$2"), "nenhum hash de senha");
	}

	@Test
	void soOTutorExportaEQuemNaoTemTokenNaoEntra() throws Exception {
		mvc.perform(get("/api/me/export").header(HttpHeaders.AUTHORIZATION, tokenOwner)).andExpect(status().isForbidden());
		mvc.perform(get("/api/me/export")).andExpect(status().isUnauthorized());
	}

	// ---------- excluir (anonimizar) ----------

	@Test
	void excluirAnonimizaAContaOsPetsEACopiaNosAgendamentosEMantemOQueEDoEstudio() throws Exception {
		String past = appointment(tutor, petTutor, "Thor", "COMPLETED", -10, 9);
		String future = appointment(tutor, petTutor, "Thor", "SCHEDULED", 5, 10);
		String othersFuture = appointment(other, petOther, "Rex", "SCHEDULED", 6, 10);
		jdbc.update("INSERT INTO waitlist_entries (provider_id, service_id, customer_id, pet_id, wanted_date) VALUES (?, ?, ?, ?, ?)", provider, service, tutor, petTutor, LocalDate.now().plusDays(8));

		delete(tokenTutor, PASSWORD).andExpect(status().isNoContent());

		// a conta
		Map<String, Object> user = jdbc.queryForMap("SELECT name, email, phone, active, anonymized_at FROM users WHERE id = ?", tutor);
		assertEquals("Conta removida", user.get("name"));
		assertEquals("removida-" + tutor + "@removida.invalid", user.get("email"));
		assertNull(user.get("phone"));
		assertEquals(false, user.get("active"));
		assertNotNull(user.get("anonymized_at"));
		// o pet e a cópia nos agendamentos
		Map<String, Object> pet = jdbc.queryForMap("SELECT name, breed, notes, health_notes, active FROM pets WHERE id = ?", petTutor);
		assertEquals("Pet removido", pet.get("name"));
		assertNull(pet.get("notes"));
		assertNull(pet.get("health_notes"));
		assertEquals(false, pet.get("active"));
		Map<String, Object> copy = jdbc.queryForMap("SELECT pet_name, pet_notes, pet_health_notes, price_cents, status FROM appointments WHERE id = ?::uuid", past);
		assertEquals("Pet removido", copy.get("pet_name"));
		assertNull(copy.get("pet_notes"));
		assertNull(copy.get("pet_health_notes"));
		assertEquals(5000, copy.get("price_cents"), "o valor é registro do estúdio e fica");
		assertEquals("COMPLETED", copy.get("status"), "o atendimento que aconteceu continua");
		// o horário futuro volta ao estúdio, com o motivo no histórico; a lista de espera esvazia
		assertEquals("CANCELLED", jdbc.queryForObject("SELECT status FROM appointments WHERE id = ?::uuid", String.class, future));
		assertEquals(1, count("SELECT count(*) FROM appointment_events WHERE appointment_id = ?::uuid AND event_type = 'CANCELLED'", future));
		assertEquals(0, count("SELECT count(*) FROM waitlist_entries WHERE customer_id = ? AND removed_at IS NULL", tutor));
		// auditoria
		assertEquals(1, count("SELECT count(*) FROM audit_log WHERE action = 'ACCOUNT_ANONYMIZED' AND actor_user_id = ? AND target_user_id = ?", tutor, tutor));
		// o outro tutor não foi tocado
		assertEquals("Rex", jdbc.queryForObject("SELECT name FROM pets WHERE id = ?", String.class, petOther));
		assertEquals("SCHEDULED", jdbc.queryForObject("SELECT status FROM appointments WHERE id = ?::uuid", String.class, othersFuture));
		assertEquals("Observação alheia", jdbc.queryForObject("SELECT notes FROM pets WHERE id = ?", String.class, petOther));
	}

	@Test
	void aContaExcluidaNaoEntraMaisENemOTokenAntigoValeEEmailPodeSerReutilizado() throws Exception {
		delete(tokenTutor, PASSWORD).andExpect(status().isNoContent());

		mvc.perform(get("/api/me").header(HttpHeaders.AUTHORIZATION, tokenTutor)).andExpect(status().isUnauthorized());
		mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(json("email", "pv-tutor@exemplo.test", "password", PASSWORD))).andExpect(status().isUnauthorized());
		mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(json("name", "Joana Nova", "email", "pv-tutor@exemplo.test", "password", PASSWORD)))
				.andExpect(status().isCreated()); // o e-mail ficou livre: a pessoa pode voltar com uma conta nova
	}

	@Test
	void senhaErradaNaoExcluiENenhumDadoMuda() throws Exception {
		delete(tokenTutor, "senha-errada-123").andExpect(status().isBadRequest());
		assertEquals("Joana Lima", jdbc.queryForObject("SELECT name FROM users WHERE id = ?", String.class, tutor));
		assertEquals("Thor", jdbc.queryForObject("SELECT name FROM pets WHERE id = ?", String.class, petTutor));
		assertEquals(0, count("SELECT count(*) FROM audit_log WHERE action = 'ACCOUNT_ANONYMIZED'"));
		mvc.perform(post("/api/me/delete-account").header(HttpHeaders.AUTHORIZATION, tokenTutor).contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isBadRequest());
	}

	@Test
	void ha_limite_de_tentativas_de_senha_para_excluir() throws Exception {
		for (int i = 0; i < 5; i++) {
			delete(tokenTutor, "senha-errada-123").andExpect(status().isBadRequest());
		}
		delete(tokenTutor, PASSWORD).andExpect(status().isTooManyRequests()).andExpect(header().exists(HttpHeaders.RETRY_AFTER));
		assertTrue(jdbc.queryForObject("SELECT active FROM users WHERE id = ?", Boolean.class, tutor), "a conta continua de pé");
	}

	@Test
	void quemNaoETutorNaoExcluiPorAquiENingemExcluiAContaDeOutro() throws Exception {
		delete(tokenOwner, PASSWORD).andExpect(status().isForbidden());
		mvc.perform(post("/api/me/delete-account").contentType(MediaType.APPLICATION_JSON).content(json("password", PASSWORD))).andExpect(status().isUnauthorized());
		delete(tokenOther, PASSWORD).andExpect(status().isNoContent()); // só a própria conta: a do tutor continua intacta
		assertEquals("Joana Lima", jdbc.queryForObject("SELECT name FROM users WHERE id = ?", String.class, tutor));
		assertTrue(jdbc.queryForObject("SELECT active FROM users WHERE id = ?", Boolean.class, tutor));
	}
}
