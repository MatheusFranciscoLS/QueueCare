package com.queuecare.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import org.springframework.test.context.TestPropertySource;

/** Troca de senha pelo próprio usuário e redefinição pelo ADMIN (ADR 0006): atomicidade, invalidação de tokens, limites e permissões. */
@TestPropertySource(properties = "queuecare.rate-limit.password-change-attempts=3")
class PasswordLifecycleIntegrationTest extends AuthIntegrationTestBase {

	private static final String NEW = "nova-senha-forte-456";

	private String login(String email, String password) throws Exception {
		MockHttpServletResponse r = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(json("email", email, "password", password)))
				.andReturn().getResponse();
		assertEquals(200, r.getStatus(), "login de " + email);
		return "Bearer " + JsonPath.read(r.getContentAsString(), "$.accessToken");
	}

	private int loginStatus(String email, String password) throws Exception {
		return mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(json("email", email, "password", password)))
				.andReturn().getResponse().getStatus();
	}

	private MockHttpServletResponse change(String bearer, String current, String next) throws Exception {
		return mvc.perform(post("/api/auth/change-password").header(HttpHeaders.AUTHORIZATION, bearer).contentType(MediaType.APPLICATION_JSON)
				.content(json("currentPassword", current, "newPassword", next))).andReturn().getResponse();
	}

	private MockHttpServletResponse reset(String bearer, Object userId, String newPassword) throws Exception {
		return mvc.perform(post("/api/admin/users/" + userId + "/reset-password").header(HttpHeaders.AUTHORIZATION, bearer).contentType(MediaType.APPLICATION_JSON)
				.content(json("newInitialPassword", newPassword))).andReturn().getResponse();
	}

	private Map<String, Object> row(UUID id) {
		return jdbc.queryForMap("SELECT role, email, active, must_change_password, token_version, password_hash FROM users WHERE id = ?", id);
	}

	@Test
	void trocaComSucessoAtualizaSenhaZeraPendenciaInvalidaTokensAntigosEAudita() throws Exception {
		UUID id = seedUser("c@exemplo.test", "CUSTOMER", true, true);
		String old = login("c@exemplo.test", PASSWORD);
		Map<String, Object> before = row(id);

		MockHttpServletResponse r = change(old, PASSWORD, NEW);

		assertEquals(200, r.getStatus());
		String body = r.getContentAsString();
		assertFalse(body.contains(NEW) || body.contains(PASSWORD) || body.contains("$2"), "nem senha nem hash na resposta");
		assertEquals(false, JsonPath.read(body, "$.mustChangePassword"));
		Map<String, Object> after = row(id);
		assertEquals(before.get("role"), after.get("role"));
		assertEquals(before.get("email"), after.get("email"));
		assertEquals(false, after.get("must_change_password"));
		assertEquals((int) before.get("token_version") + 1, after.get("token_version"));
		assertTrue(passwordEncoder.matches(NEW, (String) after.get("password_hash")) && !passwordEncoder.matches(PASSWORD, (String) after.get("password_hash")));
		Map<String, Object> audit = jdbc.queryForMap("SELECT actor_user_id, action, target_user_id, target_role FROM audit_log");
		assertEquals("PASSWORD_CHANGED", audit.get("action"));
		assertEquals(id, audit.get("actor_user_id"));
		assertEquals(id, audit.get("target_user_id"));
		assertEquals("CUSTOMER", audit.get("target_role"));

		mvc.perform(get("/api/me").header(HttpHeaders.AUTHORIZATION, old)).andExpect(status().isUnauthorized()); // o token usado na troca morreu
		String fresh = "Bearer " + JsonPath.read(body, "$.accessToken");
		mvc.perform(get("/api/appointments/mine").header(HttpHeaders.AUTHORIZATION, fresh)).andExpect(status().isOk()); // e o novo já opera
		assertEquals(401, loginStatus("c@exemplo.test", PASSWORD));
		assertEquals(200, loginStatus("c@exemplo.test", NEW));
	}

	@Test
	void campoPerfilOuEmailNoCorpoNaoTemEfeito() throws Exception {
		UUID id = seedUser("c@exemplo.test", "CUSTOMER", true);
		String token = login("c@exemplo.test", PASSWORD);

		mvc.perform(post("/api/auth/change-password").header(HttpHeaders.AUTHORIZATION, token).contentType(MediaType.APPLICATION_JSON)
				.content("{\"currentPassword\":\"" + PASSWORD + "\",\"newPassword\":\"" + NEW + "\",\"role\":\"ADMIN\",\"email\":\"outro@exemplo.test\",\"userId\":\"" + UUID.randomUUID() + "\"}"))
				.andExpect(status().isOk());

		assertEquals("CUSTOMER", row(id).get("role"));
		assertEquals("c@exemplo.test", row(id).get("email"));
	}

	@Test
	void senhaAtualErradaEh400SemMudarNadaEContaTentativasAteBloquear() throws Exception {
		UUID id = seedUser("c@exemplo.test", "CUSTOMER", true);
		String token = login("c@exemplo.test", PASSWORD);
		String hash = (String) row(id).get("password_hash");

		for (int i = 0; i < 3; i++) {
			MockHttpServletResponse r = change(token, "errada-" + i + "-1234", NEW);
			assertEquals(400, r.getStatus());
			assertTrue(r.getContentAsString().contains("INVALID_CURRENT_PASSWORD") && !r.getContentAsString().contains("errada"));
		}
		MockHttpServletResponse blocked = change(token, PASSWORD, NEW);

		assertEquals(429, blocked.getStatus(), "bloqueado mesmo com a senha certa");
		assertTrue(blocked.getHeader(HttpHeaders.RETRY_AFTER) != null);
		assertEquals(hash, row(id).get("password_hash"));
		assertEquals(0L, count("SELECT count(*) FROM audit_log"));
	}

	@Test
	void sucessoZeraAContagemDeTentativas() throws Exception {
		seedUser("c@exemplo.test", "CUSTOMER", true);
		String token = login("c@exemplo.test", PASSWORD);
		assertEquals(400, change(token, "errada-1-1234", NEW).getStatus());
		assertEquals(400, change(token, "errada-2-1234", NEW).getStatus());
		MockHttpServletResponse ok = change(token, PASSWORD, NEW);
		assertEquals(200, ok.getStatus());
		String fresh = "Bearer " + JsonPath.read(ok.getContentAsString(), "$.accessToken");

		assertEquals(400, change(fresh, "errada-3-1234", "outra-senha-forte-789").getStatus());
		assertEquals(400, change(fresh, "errada-4-1234", "outra-senha-forte-789").getStatus());
		assertEquals(200, change(fresh, NEW, "outra-senha-forte-789").getStatus(), "duas falhas depois do sucesso ainda não bloqueiam");
	}

	@Test
	void novaSenhaForaDaPoliticaOuIgualAAtualEhRecusadaSemMudarNada() throws Exception {
		UUID id = seedUser("c@exemplo.test", "CUSTOMER", true, true);
		String token = login("c@exemplo.test", PASSWORD);
		String hash = (String) row(id).get("password_hash");

		assertEquals(400, change(token, PASSWORD, "curta").getStatus());
		assertEquals(400, change(token, PASSWORD, "é".repeat(40)).getStatus()); // 80 bytes
		assertEquals(400, change(token, PASSWORD, "x".repeat(257)).getStatus());
		MockHttpServletResponse same = change(token, PASSWORD, PASSWORD);
		assertEquals(400, same.getStatus());
		assertTrue(same.getContentAsString().contains("diferente"));
		assertEquals(400, mvc.perform(post("/api/auth/change-password").header(HttpHeaders.AUTHORIZATION, token).contentType(MediaType.APPLICATION_JSON).content("{}"))
				.andReturn().getResponse().getStatus());

		Map<String, Object> after = row(id);
		assertEquals(hash, after.get("password_hash"));
		assertEquals(true, after.get("must_change_password"), "continua pendente");
		assertEquals(0, after.get("token_version"));
		assertEquals(0L, count("SELECT count(*) FROM audit_log"));
	}

	@Test
	void semTokenOuComTokenDeUsuarioInativoEhRecusado() throws Exception {
		mvc.perform(post("/api/auth/change-password").contentType(MediaType.APPLICATION_JSON).content(json("currentPassword", PASSWORD, "newPassword", NEW)))
				.andExpect(status().isUnauthorized());
		String inactive = "Bearer " + validToken(seedUser("i@exemplo.test", "CUSTOMER", false));
		assertEquals(401, change(inactive, PASSWORD, NEW).getStatus());
	}

	@Test
	void tokenComVersaoAntigaOuInvalidaEh401EAusenteContaComoZero() throws Exception {
		UUID id = seedUser("c@exemplo.test", "CUSTOMER", true);
		jdbc.update("UPDATE users SET token_version = 3 WHERE id = ?", id);
		long now = Instant.now().getEpochSecond();
		String base = "{\"iss\":\"" + ISSUER + "\",\"aud\":\"" + AUDIENCE + "\",\"sub\":\"" + id + "\",\"iat\":" + now + ",\"exp\":" + (now + 900);

		for (String claim : List.of("", ",\"ver\":2", ",\"ver\":4", ",\"ver\":\"3\"", ",\"ver\":null")) {
			int status = mvc.perform(get("/api/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + rawSignedToken(TEST_SECRET, base + claim + "}"))).andReturn().getResponse().getStatus();
			assertEquals(401, status, "claim: " + claim);
		}
		mvc.perform(get("/api/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + rawSignedToken(TEST_SECRET, base + ",\"ver\":3}"))).andExpect(status().isOk());
	}

	@Test
	void trocasSimultaneasComAMesmaSenhaAtualDaoNoMaximoUmaVitoria() throws Exception {
		UUID id = seedUser("c@exemplo.test", "CUSTOMER", true);
		String token = login("c@exemplo.test", PASSWORD);
		int threads = 8;
		ExecutorService pool = Executors.newFixedThreadPool(threads);
		try {
			CyclicBarrier startTogether = new CyclicBarrier(threads);
			List<Callable<Integer>> tasks = new ArrayList<>();
			for (int i = 0; i < threads; i++) {
				int n = i;
				tasks.add(() -> {
					startTogether.await(10, TimeUnit.SECONDS);
					return change(token, PASSWORD, "senha-nova-numero-" + n).getStatus();
				});
			}
			List<Integer> statuses = new ArrayList<>();
			for (Future<Integer> f : pool.invokeAll(tasks, 60, TimeUnit.SECONDS)) {
				statuses.add(f.get());
			}

			// A tentativa também é limitada (3 por janela neste teste): vitória única e o resto recusado, nunca 500.
			assertEquals(1, statuses.stream().filter(s -> s == 200).count(), statuses.toString());
			assertTrue(statuses.stream().allMatch(s -> s == 200 || s == 400 || s == 429), statuses.toString());
			assertEquals(1, row(id).get("token_version"));
			assertEquals(1L, count("SELECT count(*) FROM audit_log"));
			long working = 0;
			for (int n = 0; n < threads; n++) {
				if (passwordEncoder.matches("senha-nova-numero-" + n, (String) row(id).get("password_hash"))) {
					working++;
				}
			}
			assertEquals(1, working, "exatamente uma das senhas novas ficou valendo");
		} finally {
			pool.shutdownNow();
		}
	}

	// --- redefinição pelo ADMIN ---

	@Test
	void adminRedefineSenhaObrigaTrocaInvalidaTokensEAudita() throws Exception {
		String admin = "Bearer " + validToken(seedUser("admin@exemplo.test", "ADMIN", true));
		UUID target = seedUser("p@exemplo.test", "PROVIDER", true);
		String targetOld = login("p@exemplo.test", PASSWORD);

		MockHttpServletResponse r = reset(admin, target, "senha-inicial-nova-1");

		assertEquals(204, r.getStatus());
		assertEquals("", r.getContentAsString());
		Map<String, Object> after = row(target);
		assertEquals(true, after.get("must_change_password"));
		assertEquals(1, after.get("token_version"));
		assertEquals("PROVIDER", after.get("role"));
		assertEquals(true, after.get("active"));
		assertTrue(passwordEncoder.matches("senha-inicial-nova-1", (String) after.get("password_hash")));
		Map<String, Object> audit = jdbc.queryForMap("SELECT actor_user_id, action, target_user_id, target_role FROM audit_log");
		assertEquals("PASSWORD_RESET", audit.get("action"));
		assertEquals(target, audit.get("target_user_id"));
		assertEquals("PROVIDER", audit.get("target_role"));
		assertEquals(jdbc.queryForObject("SELECT id FROM users WHERE role = 'ADMIN'", UUID.class), audit.get("actor_user_id"));

		mvc.perform(get("/api/me").header(HttpHeaders.AUTHORIZATION, targetOld)).andExpect(status().isUnauthorized());
		assertEquals(401, loginStatus("p@exemplo.test", PASSWORD));
		MockHttpServletResponse login = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
				.content(json("email", "p@exemplo.test", "password", "senha-inicial-nova-1"))).andReturn().getResponse();
		assertEquals(200, login.getStatus());
		assertEquals(true, JsonPath.read(login.getContentAsString(), "$.mustChangePassword"));
	}

	@Test
	void soAdminRedefineEAdminComTrocaPendenteNao() throws Exception {
		UUID target = seedUser("p@exemplo.test", "PROVIDER", true);
		for (String role : List.of("PROVIDER", "ATTENDANT", "CUSTOMER")) {
			String bearer = "Bearer " + validToken(seedUser(role.toLowerCase() + "-x@exemplo.test", role, true));
			assertEquals(403, reset(bearer, target, "senha-inicial-nova-1").getStatus(), role);
		}
		assertEquals(401, mvc.perform(post("/api/admin/users/" + target + "/reset-password").contentType(MediaType.APPLICATION_JSON)
				.content(json("newInitialPassword", "senha-inicial-nova-1"))).andReturn().getResponse().getStatus());
		String pending = "Bearer " + validToken(seedUser("admin-p@exemplo.test", "ADMIN", true, true));
		MockHttpServletResponse r = reset(pending, target, "senha-inicial-nova-1");
		assertEquals(403, r.getStatus());
		assertTrue(r.getContentAsString().contains("PASSWORD_CHANGE_REQUIRED"));

		assertEquals(0, row(target).get("token_version"));
		assertEquals(0L, count("SELECT count(*) FROM audit_log"));
	}

	@Test
	void redefinirInexistenteEh404UsoNaPropriaContaEh409ESenhaFracaEh400() throws Exception {
		UUID adminId = seedUser("admin@exemplo.test", "ADMIN", true);
		String admin = "Bearer " + validToken(adminId);
		UUID target = seedUser("p@exemplo.test", "PROVIDER", true);
		String hash = (String) row(target).get("password_hash");

		assertEquals(404, reset(admin, UUID.randomUUID(), "senha-inicial-nova-1").getStatus());
		MockHttpServletResponse self = reset(admin, adminId, "senha-inicial-nova-1");
		assertEquals(409, self.getStatus());
		assertTrue(self.getContentAsString().contains("USE_PASSWORD_CHANGE"));
		assertEquals(400, reset(admin, target, "curta").getStatus());
		assertEquals(400, reset(admin, target, "x".repeat(257)).getStatus());
		assertEquals(400, mvc.perform(post("/api/admin/users/" + target + "/reset-password").header(HttpHeaders.AUTHORIZATION, admin)
				.contentType(MediaType.APPLICATION_JSON).content("{}")).andReturn().getResponse().getStatus());
		assertEquals(400, mvc.perform(post("/api/admin/users/nao-e-uuid/reset-password").header(HttpHeaders.AUTHORIZATION, admin)
				.contentType(MediaType.APPLICATION_JSON).content(json("newInitialPassword", "senha-inicial-nova-1"))).andReturn().getResponse().getStatus());

		assertEquals(hash, row(target).get("password_hash"));
		assertEquals(0, row(adminId).get("token_version"), "a própria senha do ADMIN não mudou");
		assertEquals(0L, count("SELECT count(*) FROM audit_log"));
	}

	@Test
	void adminPodeRedefinirOutroAdminEContaInativaContinuaInativa() throws Exception {
		String admin = "Bearer " + validToken(seedUser("admin@exemplo.test", "ADMIN", true));
		UUID otherAdmin = seedUser("admin2@exemplo.test", "ADMIN", true);
		UUID inactive = seedUser("inativo@exemplo.test", "CUSTOMER", false);

		assertEquals(204, reset(admin, otherAdmin, "senha-inicial-nova-1").getStatus());
		assertEquals(204, reset(admin, inactive, "senha-inicial-nova-1").getStatus());

		assertEquals(true, row(otherAdmin).get("must_change_password"));
		assertEquals(false, row(inactive).get("active"), "redefinir não reativa");
		assertEquals(401, loginStatus("inativo@exemplo.test", "senha-inicial-nova-1"));
	}

	@Test
	void trocaEDefinicaoSimultaneasTerminamEmEstadoCoerenteSemErro500() throws Exception {
		String admin = "Bearer " + validToken(seedUser("admin@exemplo.test", "ADMIN", true));
		UUID target = seedUser("p@exemplo.test", "PROVIDER", true);
		String token = login("p@exemplo.test", PASSWORD);
		for (int round = 0; round < 6; round++) {
			CyclicBarrier startTogether = new CyclicBarrier(2);
			ExecutorService pool = Executors.newFixedThreadPool(2);
			try {
				String currentPassword = round == 0 ? PASSWORD : "senha-inicial-nova-1";
				int version = (int) row(target).get("token_version");
				String bearer = round == 0 ? token : "Bearer " + validTokenWithVersion(target, version);
				Future<Integer> changed = pool.submit(() -> {
					startTogether.await(10, TimeUnit.SECONDS);
					return change(bearer, currentPassword, NEW + "-" + System.nanoTime()).getStatus();
				});
				Future<Integer> resetDone = pool.submit(() -> {
					startTogether.await(10, TimeUnit.SECONDS);
					return reset(admin, target, "senha-inicial-nova-1").getStatus();
				});
				int changeStatus = changed.get(60, TimeUnit.SECONDS);
				int resetStatus = resetDone.get(60, TimeUnit.SECONDS);

				assertEquals(204, resetStatus);
				assertTrue(changeStatus == 200 || changeStatus == 400 || changeStatus == 401 || changeStatus == 429, "troca: " + changeStatus);
				int expected = version + (changeStatus == 200 ? 2 : 1);
				assertEquals(expected, row(target).get("token_version"), "cada operação aplicada sobe a versão uma vez");
				// Reabre uma credencial conhecida para a próxima rodada.
				jdbc.update("UPDATE users SET password_hash = ?, must_change_password = false WHERE id = ?", passwordEncoder.encode("senha-inicial-nova-1"), target);
			} finally {
				pool.shutdownNow();
			}
		}
	}

	private static String validTokenWithVersion(UUID userId, int version) throws Exception {
		long now = Instant.now().getEpochSecond();
		return rawSignedToken(TEST_SECRET, "{\"iss\":\"" + ISSUER + "\",\"aud\":\"" + AUDIENCE + "\",\"sub\":\"" + userId + "\",\"iat\":" + now + ",\"exp\":" + (now + 900) + ",\"ver\":" + version + "}");
	}
}
