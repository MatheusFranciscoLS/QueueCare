package com.queuecare.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;

/**
 * Demonstração pública (ADR 0041): entrada com um clique em contas fictícias compartilhadas, dados repostos quando passa o prazo, e a trava das
 * operações que mexeriam na conta em si. Com a demonstração DESLIGADA (o padrão) nada disto existe: veja {@link DemoDisabledIntegrationTest}.
 */
@TestPropertySource(properties = { "queuecare.demo.enabled=true", "queuecare.demo.reset-after=60m" })
class DemoIntegrationTest extends AuthIntegrationTestBase {

	@BeforeEach
	void clearDemoState() {
		jdbc.execute("DELETE FROM demo_state");
		jdbc.execute("TRUNCATE time_off, waitlist_entries, provider_staff CASCADE");
	}

	private String enter(String profile) throws Exception {
		String body = mvc.perform(post("/api/auth/demo").contentType(MediaType.APPLICATION_JSON).content(json("profile", profile)))
				.andExpect(status().isOk()).andExpect(jsonPath("$.tokenType").value("Bearer")).andReturn().getResponse().getContentAsString();
		return "Bearer " + JsonPath.read(body, "$.accessToken");
	}

	private void makeStale() {
		jdbc.update("UPDATE demo_state SET reset_at = now() - interval '2 hours'");
	}

	@Test
	void eachButtonEntersAsItsOwnProfileWithFictionalData() throws Exception {
		String owner = enter("OWNER");
		mvc.perform(get("/api/me").header(HttpHeaders.AUTHORIZATION, owner)).andExpect(status().isOk())
				.andExpect(jsonPath("$.role").value("PROVIDER")).andExpect(jsonPath("$.email").value("dono@demo.invalid")).andExpect(jsonPath("$.mustChangePassword").value(false));
		mvc.perform(get("/api/me").header(HttpHeaders.AUTHORIZATION, enter("ATTENDANT"))).andExpect(jsonPath("$.role").value("ATTENDANT"));
		mvc.perform(get("/api/me").header(HttpHeaders.AUTHORIZATION, enter("CUSTOMER"))).andExpect(jsonPath("$.role").value("CUSTOMER"));
		assertEquals(4, count("SELECT count(*) FROM services"));
		assertTrue(count("SELECT count(*) FROM appointments") >= 12, "a agenda da demonstração deve ter agendamentos em vários estados");
		assertTrue(count("SELECT count(DISTINCT status) FROM appointments") >= 5);
		assertEquals(1, count("SELECT count(*) FROM providers WHERE published AND slug = 'estudio-bem-te-vi'"));
	}

	@Test
	void unknownProfileIsRejected() throws Exception {
		mvc.perform(post("/api/auth/demo").contentType(MediaType.APPLICATION_JSON).content(json("profile", "ADMIN"))).andExpect(status().isBadRequest());
		mvc.perform(post("/api/auth/demo").contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isBadRequest());
		assertEquals(0, count("SELECT count(*) FROM users WHERE role = 'ADMIN'"), "a demonstração nunca entra como administrador");
	}

	@Test
	void demoAccountsHaveNoPasswordThatWorksInTheLoginForm() throws Exception {
		enter("OWNER");
		for (String password : new String[] { PASSWORD, String.join("-", "Senha", "Demo", "12345"), "dono@demo.invalid" }) {
			mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(json("email", "dono@demo.invalid", "password", password)))
					.andExpect(status().isUnauthorized());
		}
	}

	@Test
	void dataIsKeptInsideTheWindowAndRestoredAfterIt() throws Exception {
		enter("OWNER");
		long scheduled = count("SELECT count(*) FROM appointments WHERE status = 'SCHEDULED'");
		jdbc.update("UPDATE providers SET business_name = 'Alterado por um visitante'");
		jdbc.update("UPDATE appointments SET status = 'CANCELLED'");

		enter("OWNER"); // dentro do prazo: o que o visitante anterior deixou continua
		assertEquals("Alterado por um visitante", jdbc.queryForObject("SELECT business_name FROM providers", String.class));
		assertEquals(0, count("SELECT count(*) FROM appointments WHERE status = 'SCHEDULED'"));

		makeStale();
		enter("OWNER"); // passou o prazo: tudo volta ao que era
		assertEquals("Estúdio Bem-te-vi", jdbc.queryForObject("SELECT business_name FROM providers", String.class));
		assertEquals(scheduled, count("SELECT count(*) FROM appointments WHERE status = 'SCHEDULED'"));
		assertEquals(4, count("SELECT count(*) FROM services"), "os serviços são recriados, nunca duplicados");
		assertEquals(1, count("SELECT count(*) FROM providers"));
		assertEquals(1, count("SELECT count(*) FROM users WHERE email = 'dono@demo.invalid'"));
	}

	@Test
	void whatVisitorsTypedDoesNotSurviveTheReset() throws Exception {
		String owner = enter("OWNER");
		mvc.perform(post("/api/agenda/customers").header(HttpHeaders.AUTHORIZATION, owner).contentType(MediaType.APPLICATION_JSON)
				.content(json("name", "Pessoa Real Digitou Aqui", "phone", "(11) 91234-5678"))).andExpect(status().isCreated());
		assertEquals(1, count("SELECT count(*) FROM users WHERE name = 'Pessoa Real Digitou Aqui'"));

		makeStale();
		enter("OWNER");
		assertEquals(0, count("SELECT count(*) FROM users WHERE name = 'Pessoa Real Digitou Aqui'"), "o nome digitado some");
		assertEquals(0, count("SELECT count(*) FROM users WHERE phone = '5511912345678'"), "o telefone digitado some");
		assertEquals(1, count("SELECT count(*) FROM users WHERE name = 'Cadastro de visitante removido' AND NOT active AND created_by_provider_id IS NULL"));
	}

	@Test
	void operationsOnTheAccountItselfAreBlockedForDemoAccountsOnly() throws Exception {
		String owner = enter("OWNER");
		for (String path : new String[] { "/api/auth/change-password", "/api/provider/staff/accounts", "/api/provider/customers/" + java.util.UUID.randomUUID() + "/reset-password" }) {
			mvc.perform(post(path).header(HttpHeaders.AUTHORIZATION, owner).contentType(MediaType.APPLICATION_JSON).content("{}"))
					.andExpect(status().isForbidden()).andExpect(jsonPath("$.reason").value("DEMO_READ_ONLY"));
		}
		mvc.perform(post("/api/me/delete-account").header(HttpHeaders.AUTHORIZATION, enter("CUSTOMER")).contentType(MediaType.APPLICATION_JSON).content("{}"))
				.andExpect(status().isForbidden()).andExpect(jsonPath("$.reason").value("DEMO_READ_ONLY"));
		assertEquals(1, count("SELECT count(*) FROM users WHERE email = 'tutora@demo.invalid' AND active"));

		// Uma conta de verdade NÃO leva o DEMO_READ_ONLY: a troca de senha segue o caminho normal (aqui, a senha atual errada).
		seedUser("real@exemplo.test", "PROVIDER", true);
		String real = "Bearer " + JsonPath.read(mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(json("email", "real@exemplo.test", "password", PASSWORD)))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.accessToken");
		String body = mvc.perform(post("/api/auth/change-password").header(HttpHeaders.AUTHORIZATION, real).contentType(MediaType.APPLICATION_JSON)
				.content(json("currentPassword", String.join("-", "errada", "999"), "newPassword", String.join("-", "outra", "senha", "forte", "456")))).andReturn().getResponse().getContentAsString();
		org.junit.jupiter.api.Assertions.assertFalse(body.contains("DEMO_READ_ONLY"));
	}

	@Test
	void demoCustomerCanBookAtTheDemoStudio() throws Exception {
		String customer = enter("CUSTOMER");
		String studio = jdbc.queryForObject("SELECT id::text FROM providers", String.class);
		String service = jdbc.queryForObject("SELECT id::text FROM services WHERE name = 'Banho'", String.class);
		String pet = jdbc.queryForObject("SELECT id::text FROM pets WHERE name = 'Mel'", String.class);
		java.time.LocalDate day = java.time.LocalDate.now(java.time.ZoneId.of("America/Sao_Paulo")).plusDays(5);
		String start = day.atTime(9, 0).toString();
		mvc.perform(post("/api/appointments").header(HttpHeaders.AUTHORIZATION, customer).contentType(MediaType.APPLICATION_JSON)
				.content("{\"providerId\":\"" + studio + "\",\"serviceId\":\"" + service + "\",\"petId\":\"" + pet + "\",\"localStart\":\"" + start + "\"}"))
				.andExpect(status().is2xxSuccessful());
	}
}
