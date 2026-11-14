package com.queuecare.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;

/** Ativar e desativar contas (ADMIN) e o prestador desativar os PRÓPRIOS atendentes (ADR 0007). */
class UserActivationIntegrationTest extends AuthIntegrationTestBase {

	private MockHttpServletResponse act(String bearer, UUID target, String action) throws Exception {
		return mvc.perform(post("/api/admin/users/" + target + "/" + action).header(HttpHeaders.AUTHORIZATION, bearer)).andReturn().getResponse();
	}

	private MockHttpServletResponse deactivateByProvider(String bearer, Object attendantId) throws Exception {
		return mvc.perform(post("/api/provider/staff/" + attendantId + "/deactivate").header(HttpHeaders.AUTHORIZATION, bearer)).andReturn().getResponse();
	}

	private String login(String email) throws Exception {
		MockHttpServletResponse r = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(json("email", email, "password", PASSWORD)))
				.andReturn().getResponse();
		assertEquals(200, r.getStatus(), email);
		return "Bearer " + JsonPath.read(r.getContentAsString(), "$.accessToken");
	}

	private int loginStatus(String email) throws Exception {
		return mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(json("email", email, "password", PASSWORD))).andReturn().getResponse().getStatus();
	}

	private Map<String, Object> row(UUID id) {
		return jdbc.queryForMap("SELECT role, active, token_version FROM users WHERE id = ?", id);
	}

	private UUID provider(String email) {
		UUID user = seedUser(email, "PROVIDER", true);
		jdbc.update("INSERT INTO providers (user_id, business_name, time_zone) VALUES (?, 'Oficina', 'America/Sao_Paulo')", user);
		return user;
	}

	private UUID attendantOf(UUID providerUser, String email) {
		UUID attendant = seedUser(email, "ATTENDANT", true);
		jdbc.update("INSERT INTO provider_staff (provider_id, user_id, role) SELECT id, ?, 'ATTENDANT' FROM providers WHERE user_id = ?", attendant, providerUser);
		return attendant;
	}

	@Test
	void adminDesativaEReativaComVersaoTokensAuditoriaEEfeitoNoNegocio() throws Exception {
		String admin = "Bearer " + validToken(seedUser("admin@exemplo.test", "ADMIN", true));
		UUID prov = provider("p@exemplo.test");
		UUID attendant = attendantOf(prov, "a@exemplo.test");
		UUID service = jdbc.queryForObject("INSERT INTO services (provider_id, name, duration_minutes) SELECT id, 'S', 60 FROM providers WHERE user_id = ? RETURNING id", UUID.class, prov);
		UUID providerId = jdbc.queryForObject("SELECT id FROM providers WHERE user_id = ?", UUID.class, prov);
		String customer = "Bearer " + validToken(seedUser("c@exemplo.test", "CUSTOMER", true));
		String attendantToken = login("a@exemplo.test");
		String providerToken = login("p@exemplo.test");
		String slots = "/api/providers/" + providerId + "/slots";

		MockHttpServletResponse r = act(admin, prov, "deactivate");

		assertEquals(204, r.getStatus());
		assertEquals(false, row(prov).get("active"));
		assertEquals(1, row(prov).get("token_version"));
		Map<String, Object> audit = jdbc.queryForMap("SELECT actor_user_id, action, target_user_id, target_role FROM audit_log");
		assertEquals("USER_DEACTIVATED", audit.get("action"));
		assertEquals(prov, audit.get("target_user_id"));
		assertEquals("PROVIDER", audit.get("target_role"));
		mvc.perform(get("/api/me").header(HttpHeaders.AUTHORIZATION, providerToken)).andExpect(status().isUnauthorized());
		assertEquals(401, loginStatus("p@exemplo.test"));
		// o negócio some para clientes e a equipe do prestador desativado não opera
		mvc.perform(get(slots).param("serviceId", service.toString()).param("date", "2030-01-01").header(HttpHeaders.AUTHORIZATION, customer)).andExpect(status().isNotFound());
		mvc.perform(get("/api/agenda").param("date", "2030-01-01").header(HttpHeaders.AUTHORIZATION, attendantToken)).andExpect(status().isForbidden());

		assertEquals(204, act(admin, prov, "activate").getStatus());

		assertEquals(true, row(prov).get("active"));
		assertEquals(2, row(prov).get("token_version"));
		assertEquals("USER_ACTIVATED", jdbc.queryForObject("SELECT action FROM audit_log ORDER BY occurred_at DESC LIMIT 1", String.class));
		mvc.perform(get("/api/me").header(HttpHeaders.AUTHORIZATION, providerToken)).andExpect(status().isUnauthorized()); // token antigo NÃO ressuscita
		assertEquals(200, loginStatus("p@exemplo.test"));
		mvc.perform(get(slots).param("serviceId", service.toString()).param("date", "2030-01-01").header(HttpHeaders.AUTHORIZATION, customer)).andExpect(status().isOk());
		mvc.perform(get("/api/agenda").param("date", "2030-01-01").header(HttpHeaders.AUTHORIZATION, login("a@exemplo.test"))).andExpect(status().isOk());
	}

	@Test
	void reservaEmPrestadorDesativadoEhRecusada() throws Exception {
		String admin = "Bearer " + validToken(seedUser("admin@exemplo.test", "ADMIN", true));
		UUID prov = provider("p@exemplo.test");
		UUID providerId = jdbc.queryForObject("SELECT id FROM providers WHERE user_id = ?", UUID.class, prov);
		UUID service = jdbc.queryForObject("INSERT INTO services (provider_id, name, duration_minutes) VALUES (?, 'S', 60) RETURNING id", UUID.class, providerId);
		String customer = "Bearer " + validToken(seedUser("c@exemplo.test", "CUSTOMER", true));
		act(admin, prov, "deactivate");

		mvc.perform(post("/api/appointments").header(HttpHeaders.AUTHORIZATION, customer).contentType(MediaType.APPLICATION_JSON)
				.content("{\"providerId\":\"" + providerId + "\",\"serviceId\":\"" + service + "\",\"localStart\":\"2030-01-07T10:00:00\"}"))
				.andExpect(status().isNotFound());
		assertEquals(0L, count("SELECT count(*) FROM appointments"));
	}

	@Test
	void repetirNaoMudaNadaNemAuditaDeNovo() throws Exception {
		String admin = "Bearer " + validToken(seedUser("admin@exemplo.test", "ADMIN", true));
		UUID target = seedUser("c@exemplo.test", "CUSTOMER", true);

		assertEquals(204, act(admin, target, "activate").getStatus()); // já ativo
		assertEquals(0L, count("SELECT count(*) FROM audit_log"));
		assertEquals(0, row(target).get("token_version"));
		assertEquals(204, act(admin, target, "deactivate").getStatus());
		assertEquals(204, act(admin, target, "deactivate").getStatus()); // já inativo
		assertEquals(1L, count("SELECT count(*) FROM audit_log"));
		assertEquals(1, row(target).get("token_version"));
	}

	@Test
	void naoDesativaASiMesmoNemEncontraInexistenteESoAdminOpera() throws Exception {
		UUID adminId = seedUser("admin@exemplo.test", "ADMIN", true);
		String admin = "Bearer " + validToken(adminId);
		UUID target = seedUser("c@exemplo.test", "CUSTOMER", true);

		MockHttpServletResponse self = act(admin, adminId, "deactivate");
		assertEquals(409, self.getStatus());
		assertTrue(self.getContentAsString().contains("CANNOT_DEACTIVATE_SELF"));
		assertEquals(404, act(admin, UUID.randomUUID(), "deactivate").getStatus());
		assertEquals(404, act(admin, UUID.randomUUID(), "activate").getStatus());
		for (String role : List.of("PROVIDER", "ATTENDANT", "CUSTOMER")) {
			String bearer = "Bearer " + validToken(seedUser(role.toLowerCase() + "-x@exemplo.test", role, true));
			assertEquals(403, act(bearer, target, "deactivate").getStatus(), role);
			assertEquals(403, act(bearer, target, "activate").getStatus(), role);
		}
		assertEquals(401, mvc.perform(post("/api/admin/users/" + target + "/deactivate")).andReturn().getResponse().getStatus());
		String pending = "Bearer " + validToken(seedUser("admin-p@exemplo.test", "ADMIN", true, true));
		MockHttpServletResponse r = act(pending, target, "deactivate");
		assertEquals(403, r.getStatus());
		assertTrue(r.getContentAsString().contains("PASSWORD_CHANGE_REQUIRED"));

		assertEquals(true, row(adminId).get("active"));
		assertEquals(true, row(target).get("active"));
		assertEquals(0L, count("SELECT count(*) FROM audit_log"));
	}

	@Test
	void adminPodeDesativarOutroAdminEEleDeixaDeEntrar() throws Exception {
		String admin = "Bearer " + validToken(seedUser("admin@exemplo.test", "ADMIN", true));
		UUID other = seedUser("admin2@exemplo.test", "ADMIN", true);

		assertEquals(204, act(admin, other, "deactivate").getStatus());

		assertEquals(401, loginStatus("admin2@exemplo.test"));
		assertEquals(1L, count("SELECT count(*) FROM users WHERE role = 'ADMIN' AND active"));
	}

	@Test
	void doisAdminsSeDesativandoAoMesmoTempoNuncaDeixamZeroAtivos() throws Exception {
		UUID a = seedUser("a@exemplo.test", "ADMIN", true);
		UUID b = seedUser("b@exemplo.test", "ADMIN", true);
		String tokenA = "Bearer " + validToken(a);
		String tokenB = "Bearer " + validToken(b);
		for (int round = 0; round < 15; round++) {
			jdbc.update("UPDATE users SET active = true WHERE id IN (?, ?)", a, b);
			ExecutorService pool = Executors.newFixedThreadPool(2);
			try {
				CyclicBarrier startTogether = new CyclicBarrier(2);
				Future<Integer> aKillsB = pool.submit(() -> {
					startTogether.await(10, TimeUnit.SECONDS);
					return act(tokenA, b, "deactivate").getStatus();
				});
				Future<Integer> bKillsA = pool.submit(() -> {
					startTogether.await(10, TimeUnit.SECONDS);
					return act(tokenB, a, "deactivate").getStatus();
				});
				int s1 = aKillsB.get(60, TimeUnit.SECONDS);
				int s2 = bKillsA.get(60, TimeUnit.SECONDS);

				assertEquals(1, List.of(s1, s2).stream().filter(s -> s == 204).count(), "exatamente um vence: " + s1 + " e " + s2);
				assertTrue(List.of(s1, s2).stream().allMatch(s -> s == 204 || s == 401 || s == 403 || s == 409), s1 + " e " + s2);
				assertEquals(1L, count("SELECT count(*) FROM users WHERE role = 'ADMIN' AND active"), "sobra exatamente um ADMIN ativo");
			} finally {
				pool.shutdownNow();
			}
		}
	}

	@Test
	void falhaAoAuditarDesfazADesativacao() throws Exception {
		String admin = "Bearer " + validToken(seedUser("admin@exemplo.test", "ADMIN", true));
		UUID target = seedUser("c@exemplo.test", "CUSTOMER", true);
		jdbc.execute("CREATE FUNCTION test_fail_audit() RETURNS trigger AS $$ BEGIN RAISE EXCEPTION 'falha simulada'; END; $$ LANGUAGE plpgsql");
		jdbc.execute("CREATE TRIGGER test_fail_audit_trg BEFORE INSERT ON audit_log FOR EACH ROW EXECUTE FUNCTION test_fail_audit()");
		try {
			assertThrows(jakarta.servlet.ServletException.class, () -> act(admin, target, "deactivate"));
		} finally {
			jdbc.execute("DROP TRIGGER test_fail_audit_trg ON audit_log");
			jdbc.execute("DROP FUNCTION test_fail_audit()");
		}

		assertEquals(true, row(target).get("active"), "a desativação foi desfeita junto");
		assertEquals(0, row(target).get("token_version"));
	}

	// --- prestador desativa o PRÓPRIO atendente ---

	@Test
	void prestadorDesativaAtendenteProprioDesfazVinculoEAudita() throws Exception {
		UUID prov = provider("p@exemplo.test");
		UUID attendant = attendantOf(prov, "a@exemplo.test");
		String provider = "Bearer " + validToken(prov);
		String attendantToken = login("a@exemplo.test");

		assertEquals(204, deactivateByProvider(provider, attendant).getStatus());

		assertEquals(false, row(attendant).get("active"));
		assertEquals(1, row(attendant).get("token_version"));
		assertEquals(0L, count("SELECT count(*) FROM provider_staff WHERE user_id = ?", attendant));
		Map<String, Object> audit = jdbc.queryForMap("SELECT actor_user_id, action, target_user_id, target_role FROM audit_log");
		assertEquals(prov, audit.get("actor_user_id"));
		assertEquals("USER_DEACTIVATED", audit.get("action"));
		assertEquals(attendant, audit.get("target_user_id"));
		assertEquals("ATTENDANT", audit.get("target_role"));
		mvc.perform(get("/api/agenda").param("date", "2030-01-01").header(HttpHeaders.AUTHORIZATION, attendantToken)).andExpect(status().isUnauthorized());
		assertEquals(401, loginStatus("a@exemplo.test"));
	}

	@Test
	void prestadorNaoDesativaAtendenteDeOutroNemNaoAtendenteNemInexistenteTudoIgual404() throws Exception {
		UUID prov1 = provider("p1@exemplo.test");
		UUID prov2 = provider("p2@exemplo.test");
		UUID foreignAttendant = attendantOf(prov2, "a2@exemplo.test");
		UUID unlinkedAttendant = seedUser("solto@exemplo.test", "ATTENDANT", true);
		UUID customer = seedUser("c@exemplo.test", "CUSTOMER", true);
		UUID admin = seedUser("admin@exemplo.test", "ADMIN", true);
		String provider = "Bearer " + validToken(prov1);

		List<String> bodies = new ArrayList<>();
		for (Object target : List.of(foreignAttendant, unlinkedAttendant, customer, admin, prov2, UUID.randomUUID())) {
			MockHttpServletResponse r = deactivateByProvider(provider, target);
			assertEquals(404, r.getStatus(), String.valueOf(target));
			bodies.add(r.getContentAsString().replaceAll("\"instance\":\"[^\"]*\",?", "")); // o caminho (com o id) muda; o resto é igual
		}

		assertEquals(1, bodies.stream().distinct().count(), "a mesma resposta para qualquer alvo que não seja atendente seu: " + bodies);
		for (UUID id : List.of(foreignAttendant, unlinkedAttendant, customer, admin, prov2)) {
			assertEquals(true, row(id).get("active"));
			assertEquals(0, row(id).get("token_version"));
		}
		assertEquals(1L, count("SELECT count(*) FROM provider_staff"), "o vínculo alheio ficou");
		assertEquals(0L, count("SELECT count(*) FROM audit_log"));
	}

	@Test
	void soPrestadorDonoDesativaAtendenteEPendenteNao() throws Exception {
		UUID prov = provider("p@exemplo.test");
		UUID attendant = attendantOf(prov, "a@exemplo.test");
		for (String role : List.of("ADMIN", "ATTENDANT", "CUSTOMER")) {
			String bearer = "Bearer " + validToken(seedUser(role.toLowerCase() + "-x@exemplo.test", role, true));
			assertEquals(403, deactivateByProvider(bearer, attendant).getStatus(), role);
		}
		assertEquals(401, mvc.perform(post("/api/provider/staff/" + attendant + "/deactivate")).andReturn().getResponse().getStatus());
		UUID pendingProvider = seedUser("pp@exemplo.test", "PROVIDER", true, true);
		assertEquals(403, deactivateByProvider("Bearer " + validToken(pendingProvider), attendant).getStatus());

		assertEquals(true, row(attendant).get("active"));
		assertEquals(1L, count("SELECT count(*) FROM provider_staff"));
	}

	@Test
	void falhaAoAuditarDesfazTambemODesvinculoDoAtendente() throws Exception {
		UUID prov = provider("p@exemplo.test");
		UUID attendant = attendantOf(prov, "a@exemplo.test");
		jdbc.execute("CREATE FUNCTION test_fail_audit() RETURNS trigger AS $$ BEGIN RAISE EXCEPTION 'falha simulada'; END; $$ LANGUAGE plpgsql");
		jdbc.execute("CREATE TRIGGER test_fail_audit_trg BEFORE INSERT ON audit_log FOR EACH ROW EXECUTE FUNCTION test_fail_audit()");
		try {
			assertThrows(jakarta.servlet.ServletException.class, () -> deactivateByProvider("Bearer " + validToken(prov), attendant));
		} finally {
			jdbc.execute("DROP TRIGGER test_fail_audit_trg ON audit_log");
			jdbc.execute("DROP FUNCTION test_fail_audit()");
		}

		assertEquals(true, row(attendant).get("active"));
		assertEquals(1L, count("SELECT count(*) FROM provider_staff WHERE user_id = ?", attendant));
	}

	@Test
	void prestadorEAdminDesativandoOMesmoAtendenteAoMesmoTempoNaoDao500() throws Exception {
		UUID adminId = seedUser("admin@exemplo.test", "ADMIN", true);
		String admin = "Bearer " + validToken(adminId);
		UUID prov = provider("p@exemplo.test");
		String provider = "Bearer " + validToken(prov);
		for (int round = 0; round < 8; round++) {
			UUID attendant = attendantOf(prov, "a" + round + "@exemplo.test");
			ExecutorService pool = Executors.newFixedThreadPool(2);
			try {
				CyclicBarrier startTogether = new CyclicBarrier(2);
				List<Callable<Integer>> tasks = List.of(() -> {
					startTogether.await(10, TimeUnit.SECONDS);
					return deactivateByProvider(provider, attendant).getStatus();
				}, () -> {
					startTogether.await(10, TimeUnit.SECONDS);
					return act(admin, attendant, "deactivate").getStatus();
				});
				List<Integer> statuses = new ArrayList<>();
				for (Future<Integer> f : pool.invokeAll(tasks, 60, TimeUnit.SECONDS)) {
					statuses.add(f.get());
				}
				assertTrue(statuses.stream().allMatch(s -> s == 204 || s == 404), statuses.toString());
				assertEquals(false, row(attendant).get("active"));
				assertEquals(1, row(attendant).get("token_version"), "desativada uma única vez: " + statuses);
			} finally {
				pool.shutdownNow();
			}
		}
	}

	@Test
	void tokenDeContaDesativadaEReativadaPeloAdminNaoRessuscitaMesmoComVersaoAntigaNoTokenNovoAusente() throws Exception {
		String admin = "Bearer " + validToken(seedUser("admin@exemplo.test", "ADMIN", true));
		UUID target = seedUser("c@exemplo.test", "CUSTOMER", true);
		long now = Instant.now().getEpochSecond();
		String sem = rawSignedToken(TEST_SECRET, "{\"iss\":\"" + ISSUER + "\",\"aud\":\"" + AUDIENCE + "\",\"sub\":\"" + target + "\",\"iat\":" + now + ",\"exp\":" + (now + 900) + "}");

		mvc.perform(get("/api/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + sem)).andExpect(status().isOk());
		act(admin, target, "deactivate");
		act(admin, target, "activate");

		mvc.perform(get("/api/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + sem)).andExpect(status().isUnauthorized());
		mvc.perform(get("/api/me").header(HttpHeaders.AUTHORIZATION, login("c@exemplo.test"))).andExpect(status().isOk()).andExpect(jsonPath("$.email").value("c@exemplo.test"));
	}
}
