package com.queuecare.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** O ADMIN cria prestadores (usuário + negócio) de forma atômica; ninguém além do ADMIN pode; ADMIN não toca em agenda nem clientes. */
class AdminProvisioningIntegrationTest extends AuthIntegrationTestBase {

	private static final String TZ = "America/Sao_Paulo";
	private String admin;

	@BeforeEach
	void seed() {
		admin = "Bearer " + validToken(seedUser("admin@exemplo.test", "ADMIN", true));
	}

	private static String body(String email, String password, String businessName, String timeZone) {
		return "{\"name\":\"Oficina Central\",\"email\":\"" + email + "\",\"initialPassword\":\"" + password + "\",\"businessName\":\"" + businessName
				+ "\",\"timeZone\":\"" + timeZone + "\",\"minimumNoticeMinutes\":30,\"cancellationDeadlineMinutes\":120}";
	}

	private ResultActions send(MockHttpServletRequestBuilder builder, String bearer, String json) throws Exception {
		builder = builder.header(HttpHeaders.AUTHORIZATION, bearer);
		if (json != null) {
			builder = builder.contentType(MediaType.APPLICATION_JSON).content(json);
		}
		return mvc.perform(builder);
	}

	private ResultActions createProvider(String bearer, String json) throws Exception {
		return send(post("/api/admin/providers"), bearer, json);
	}

	private void assertNothingCreatedBesidesAdmin() {
		assertEquals(1L, count("SELECT count(*) FROM users"), "só o ADMIN existe");
		assertEquals(0L, count("SELECT count(*) FROM providers"));
		assertEquals(0L, count("SELECT count(*) FROM audit_log"));
	}

	@Test
	void adminCriaPrestadorComNegocioEAuditoriaESenhaInicialObrigaTroca() throws Exception {
		String response = createProvider(admin, body("  Oficina@Exemplo.TEST ", "senha-inicial-123", "Oficina Central", TZ))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.email").value("oficina@exemplo.test"))
				.andExpect(jsonPath("$.mustChangePassword").value(true))
				.andReturn().getResponse().getContentAsString();

		assertFalse(response.contains("senha-inicial-123") || response.toLowerCase().contains("hash") || response.contains("$2"), "a resposta não carrega senha nem hash");
		UUID userId = UUID.fromString(JsonPath.read(response, "$.userId"));
		Map<String, Object> user = jdbc.queryForMap("SELECT role, active, must_change_password, password_hash FROM users WHERE id = ?", userId);
		assertEquals("PROVIDER", user.get("role"));
		assertEquals(true, user.get("active"));
		assertEquals(true, user.get("must_change_password"));
		assertTrue(((String) user.get("password_hash")).startsWith("$2"), "senha guardada só como hash BCrypt");
		assertEquals(1L, count("SELECT count(*) FROM providers WHERE user_id = ? AND business_name = 'Oficina Central' AND time_zone = ?", userId, TZ));

		Map<String, Object> audit = jdbc.queryForMap("SELECT actor_user_id, action, target_user_id, target_role FROM audit_log");
		assertEquals("PROVIDER_ACCOUNT_CREATED", audit.get("action"));
		assertEquals(userId, audit.get("target_user_id"));
		assertEquals("PROVIDER", audit.get("target_role"));
		assertEquals(jdbc.queryForObject("SELECT id FROM users WHERE role = 'ADMIN'", UUID.class), audit.get("actor_user_id"));
	}

	@Test
	void prestadorCriadoFazLoginEVeQueAindaPrecisaTrocarASenha() throws Exception {
		createProvider(admin, body("oficina@exemplo.test", "senha-inicial-123", "Oficina", TZ)).andExpect(status().isCreated());

		mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(json("email", "oficina@exemplo.test", "password", "senha-inicial-123")))
				.andExpect(status().isOk()).andExpect(jsonPath("$.mustChangePassword").value(true));
	}

	@Test
	void perfilEhSempreProviderMesmoQueARequisicaoTenteOutro() throws Exception {
		String json = body("oficina@exemplo.test", "senha-inicial-123", "Oficina", TZ).replace("{", "{\"role\":\"ADMIN\",\"mustChangePassword\":false,\"active\":false,");

		createProvider(admin, json).andExpect(status().isCreated());

		Map<String, Object> user = jdbc.queryForMap("SELECT role, active, must_change_password FROM users WHERE email = 'oficina@exemplo.test'");
		assertEquals("PROVIDER", user.get("role"));
		assertEquals(true, user.get("active"));
		assertEquals(true, user.get("must_change_password"));
		assertEquals(1L, count("SELECT count(*) FROM users WHERE role = 'ADMIN'"), "nenhum ADMIN extra");
	}

	@Test
	void emailJaCadastradoEh409ENadaEhGravado() throws Exception {
		seedUser("oficina@exemplo.test", "CUSTOMER", true);

		createProvider(admin, body("OFICINA@exemplo.test", "senha-inicial-123", "Oficina", TZ)).andExpect(status().isConflict());

		assertEquals(2L, count("SELECT count(*) FROM users"), "só o ADMIN e o cliente que já existiam");
		assertEquals(0L, count("SELECT count(*) FROM providers"));
		assertEquals(0L, count("SELECT count(*) FROM audit_log"));
	}

	@Test
	void negocioInvalidoDesfazTambemAContaDeUsuario() throws Exception {
		createProvider(admin, body("oficina@exemplo.test", "senha-inicial-123", "Oficina", "Marte/Olimpo")).andExpect(status().isBadRequest());
		createProvider(admin, body("oficina@exemplo.test", "senha-inicial-123", "Oficina", TZ).replace("\"minimumNoticeMinutes\":30", "\"minimumNoticeMinutes\":-5"))
				.andExpect(status().isBadRequest());

		assertNothingCreatedBesidesAdmin();
	}

	@Test
	void senhaForaDaPoliticaOuCamposAusentesNaoCriamNada() throws Exception {
		createProvider(admin, body("a@exemplo.test", "curta", "Oficina", TZ)).andExpect(status().isBadRequest());
		createProvider(admin, body("b@exemplo.test", "x".repeat(257), "Oficina", TZ)).andExpect(status().isBadRequest());
		createProvider(admin, body("c@exemplo.test", "é".repeat(40), "Oficina", TZ)).andExpect(status().isBadRequest()); // 80 bytes > 72
		createProvider(admin, "{\"name\":\"x\"}").andExpect(status().isBadRequest());
		createProvider(admin, body("nao-e-email", "senha-inicial-123", "Oficina", TZ)).andExpect(status().isBadRequest());

		assertNothingCreatedBesidesAdmin();
	}

	@Test
	void falhaAoGravarAAuditoriaDesfazTudoOuSejaOperacaoEAuditoriaAndamJuntas() throws Exception {
		jdbc.execute("CREATE FUNCTION test_fail_audit() RETURNS trigger AS $$ BEGIN RAISE EXCEPTION 'falha simulada'; END; $$ LANGUAGE plpgsql");
		jdbc.execute("CREATE TRIGGER test_fail_audit_trg BEFORE INSERT ON audit_log FOR EACH ROW EXECUTE FUNCTION test_fail_audit()");
		try {
			assertThrows(jakarta.servlet.ServletException.class, () -> createProvider(admin, body("oficina@exemplo.test", "senha-inicial-123", "Oficina", TZ)));
		} finally {
			jdbc.execute("DROP TRIGGER test_fail_audit_trg ON audit_log");
			jdbc.execute("DROP FUNCTION test_fail_audit()");
		}

		assertNothingCreatedBesidesAdmin();
	}

	@Test
	void doisCadastrosSimultaneosDoMesmoEmailCriamUmSo() throws Exception {
		ExecutorService pool = Executors.newFixedThreadPool(6);
		try {
			CyclicBarrier startTogether = new CyclicBarrier(6);
			List<Callable<Integer>> tasks = new ArrayList<>();
			for (int i = 0; i < 6; i++) {
				int n = i;
				tasks.add(() -> {
					startTogether.await(10, TimeUnit.SECONDS);
					return createProvider(admin, body("mesmo@exemplo.test", "senha-inicial-123", "Oficina " + n, TZ)).andReturn().getResponse().getStatus();
				});
			}
			List<Integer> statuses = new ArrayList<>();
			for (Future<Integer> f : pool.invokeAll(tasks, 60, TimeUnit.SECONDS)) {
				statuses.add(f.get());
			}

			assertEquals(1, statuses.stream().filter(s -> s == 201).count(), "exatamente um cria: " + statuses);
			assertEquals(5, statuses.stream().filter(s -> s == 409).count(), "os demais recebem 409: " + statuses);
			assertEquals(1L, count("SELECT count(*) FROM providers"));
			assertEquals(1L, count("SELECT count(*) FROM audit_log"));
		} finally {
			pool.shutdownNow();
		}
	}

	@Test
	void soOAdminCriaPrestador() throws Exception {
		String json = body("oficina@exemplo.test", "senha-inicial-123", "Oficina", TZ);
		for (String role : List.of("PROVIDER", "ATTENDANT", "CUSTOMER")) {
			String bearer = "Bearer " + validToken(seedUser(role.toLowerCase() + "@exemplo.test", role, true));
			createProvider(bearer, json).andExpect(status().isForbidden());
		}
		mvc.perform(post("/api/admin/providers").contentType(MediaType.APPLICATION_JSON).content(json)).andExpect(status().isUnauthorized());
		// ADMIN com troca de senha pendente também não opera.
		String pending = "Bearer " + validToken(seedUser("admin2@exemplo.test", "ADMIN", true, true));
		createProvider(pending, json).andExpect(status().isForbidden()).andExpect(jsonPath("$.reason").value("PASSWORD_CHANGE_REQUIRED"));
		// ADMIN inativo é recusado.
		String inactive = "Bearer " + validToken(seedUser("admin3@exemplo.test", "ADMIN", false));
		createProvider(inactive, json).andExpect(status().isUnauthorized());

		assertEquals(0L, count("SELECT count(*) FROM providers"));
		assertEquals(0L, count("SELECT count(*) FROM audit_log"));
	}

	/** O ADMIN administra contas, mas NÃO enxerga agendas, clientes, serviços nem equipes: toda rota de negócio recusa. */
	@Test
	void adminNaoAcessaRotasDeNegocio() throws Exception {
		String someId = UUID.randomUUID().toString();
		send(get("/api/agenda").param("date", "2030-01-01"), admin, null).andExpect(status().isForbidden());
		send(get("/api/appointments/mine"), admin, null).andExpect(status().isForbidden());
		send(post("/api/appointments"), admin, "{}").andExpect(status().isForbidden());
		send(post("/api/appointments/" + someId + "/cancel"), admin, null).andExpect(status().isForbidden());
		send(get("/api/providers/" + someId + "/slots").param("serviceId", someId).param("date", "2030-01-01"), admin, null).andExpect(status().isForbidden());
		send(get("/api/provider"), admin, null).andExpect(status().isForbidden());
		send(post("/api/provider"), admin, "{}").andExpect(status().isForbidden());
		send(put("/api/provider/availability"), admin, "[]").andExpect(status().isForbidden());
		send(get("/api/provider/staff"), admin, null).andExpect(status().isForbidden());
		send(post("/api/provider/staff/accounts"), admin, "{}").andExpect(status().isForbidden());
		send(get("/api/me"), admin, null).andExpect(status().isOk()).andExpect(jsonPath("$.role").value("ADMIN"));
	}

}
