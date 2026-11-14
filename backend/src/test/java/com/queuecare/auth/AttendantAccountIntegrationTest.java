package com.queuecare.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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

/** O prestador cria a conta de um atendente NOVO já vinculada ao próprio negócio, de forma atômica e auditada. */
class AttendantAccountIntegrationTest extends AuthIntegrationTestBase {

	private String provider;
	private UUID providerUserId;
	private UUID providerId;

	@BeforeEach
	void seed() {
		providerUserId = seedUser("prestador@exemplo.test", "PROVIDER", true);
		providerId = jdbc.queryForObject(
				"INSERT INTO providers (user_id, business_name, time_zone) VALUES (?, 'Oficina', 'America/Sao_Paulo') RETURNING id", UUID.class, providerUserId);
		provider = "Bearer " + validToken(providerUserId);
	}

	private static String body(String email, String password) {
		return "{\"name\":\"Ana Atendente\",\"email\":\"" + email + "\",\"initialPassword\":\"" + password + "\"}";
	}

	private ResultActions send(MockHttpServletRequestBuilder builder, String bearer, String json) throws Exception {
		builder = builder.header(HttpHeaders.AUTHORIZATION, bearer);
		if (json != null) {
			builder = builder.contentType(MediaType.APPLICATION_JSON).content(json);
		}
		return mvc.perform(builder);
	}

	private ResultActions createAccount(String bearer, String json) throws Exception {
		return send(post("/api/provider/staff/accounts"), bearer, json);
	}

	@Test
	void prestadorCriaAtendenteVinculadoAoProprioNegocioComAuditoria() throws Exception {
		String response = createAccount(provider, body("  Ana@Exemplo.TEST ", "senha-inicial-123")).andExpect(status().isCreated())
				.andExpect(jsonPath("$.email").value("ana@exemplo.test")).andReturn().getResponse().getContentAsString();

		assertFalse(response.contains("senha-inicial-123") || response.contains("$2"), "nem senha nem hash na resposta");
		UUID attendantId = UUID.fromString(JsonPath.read(response, "$.userId"));
		Map<String, Object> user = jdbc.queryForMap("SELECT role, active, must_change_password FROM users WHERE id = ?", attendantId);
		assertEquals("ATTENDANT", user.get("role"));
		assertEquals(true, user.get("active"));
		assertEquals(true, user.get("must_change_password"));
		assertEquals(providerId, jdbc.queryForObject("SELECT provider_id FROM provider_staff WHERE user_id = ?", UUID.class, attendantId));
		Map<String, Object> audit = jdbc.queryForMap("SELECT actor_user_id, action, target_user_id, target_role FROM audit_log");
		assertEquals(providerUserId, audit.get("actor_user_id"));
		assertEquals("ATTENDANT_ACCOUNT_CREATED", audit.get("action"));
		assertEquals(attendantId, audit.get("target_user_id"));
		assertEquals("ATTENDANT", audit.get("target_role"));
	}

	@Test
	void perfilEProvedorNuncaVemDaRequisicao() throws Exception {
		String json = body("ana@exemplo.test", "senha-inicial-123").replace("{", "{\"role\":\"ADMIN\",\"providerId\":\"" + UUID.randomUUID() + "\",\"mustChangePassword\":false,");

		createAccount(provider, json).andExpect(status().isCreated());

		assertEquals("ATTENDANT", jdbc.queryForObject("SELECT role FROM users WHERE email = 'ana@exemplo.test'", String.class));
		assertEquals(providerId, jdbc.queryForObject("SELECT provider_id FROM provider_staff", UUID.class), "vinculada ao negócio de QUEM chamou");
		assertEquals(true, jdbc.queryForObject("SELECT must_change_password FROM users WHERE email = 'ana@exemplo.test'", Boolean.class));
		assertEquals(0L, count("SELECT count(*) FROM users WHERE role = 'ADMIN'"));
	}

	@Test
	void emailJaUsadoPorQualquerContaEh409SemAlterarNada() throws Exception {
		UUID existing = seedUser("ana@exemplo.test", "CUSTOMER", true);
		String hashBefore = jdbc.queryForObject("SELECT password_hash FROM users WHERE id = ?", String.class, existing);

		createAccount(provider, body("ANA@exemplo.test", "senha-inicial-123")).andExpect(status().isConflict());

		assertEquals("CUSTOMER", jdbc.queryForObject("SELECT role FROM users WHERE id = ?", String.class, existing), "a conta existente não virou atendente");
		assertEquals(hashBefore, jdbc.queryForObject("SELECT password_hash FROM users WHERE id = ?", String.class, existing), "nem teve a senha trocada");
		assertEquals(0L, count("SELECT count(*) FROM provider_staff"));
		assertEquals(0L, count("SELECT count(*) FROM audit_log"));
	}

	@Test
	void senhaForaDaPoliticaOuDadosInvalidosNaoCriamNada() throws Exception {
		createAccount(provider, body("a@exemplo.test", "curta")).andExpect(status().isBadRequest());
		createAccount(provider, body("b@exemplo.test", "x".repeat(257))).andExpect(status().isBadRequest());
		createAccount(provider, body("nao-e-email", "senha-inicial-123")).andExpect(status().isBadRequest());
		createAccount(provider, "{\"name\":\"\",\"email\":\"c@exemplo.test\",\"initialPassword\":\"senha-inicial-123\"}").andExpect(status().isBadRequest());

		assertEquals(1L, count("SELECT count(*) FROM users"), "só o prestador");
		assertEquals(0L, count("SELECT count(*) FROM audit_log"));
	}

	@Test
	void falhaAoVincularDesfazAContaCriada() throws Exception {
		jdbc.execute("CREATE FUNCTION test_fail_link() RETURNS trigger AS $$ BEGIN RAISE EXCEPTION 'falha simulada'; END; $$ LANGUAGE plpgsql");
		jdbc.execute("CREATE TRIGGER test_fail_link_trg BEFORE INSERT ON provider_staff FOR EACH ROW EXECUTE FUNCTION test_fail_link()");
		try {
			assertThrows(jakarta.servlet.ServletException.class, () -> createAccount(provider, body("ana@exemplo.test", "senha-inicial-123")));
		} finally {
			jdbc.execute("DROP TRIGGER test_fail_link_trg ON provider_staff");
			jdbc.execute("DROP FUNCTION test_fail_link()");
		}

		assertEquals(1L, count("SELECT count(*) FROM users"), "a conta do atendente foi desfeita junto");
		assertEquals(0L, count("SELECT count(*) FROM audit_log"));
	}

	@Test
	void soOPrestadorDonoCria() throws Exception {
		String json = body("ana@exemplo.test", "senha-inicial-123");
		for (String role : List.of("ADMIN", "ATTENDANT", "CUSTOMER")) {
			String bearer = "Bearer " + validToken(seedUser(role.toLowerCase() + "@exemplo.test", role, true));
			createAccount(bearer, json).andExpect(status().isForbidden());
		}
		mvc.perform(post("/api/provider/staff/accounts").contentType(MediaType.APPLICATION_JSON).content(json)).andExpect(status().isUnauthorized());
		String pending = "Bearer " + validToken(seedUser("pendente@exemplo.test", "PROVIDER", true, true));
		createAccount(pending, json).andExpect(status().isForbidden()).andExpect(jsonPath("$.reason").value("PASSWORD_CHANGE_REQUIRED"));

		assertEquals(0L, count("SELECT count(*) FROM provider_staff"));
		assertEquals(0L, count("SELECT count(*) FROM audit_log"));
	}

	@Test
	void prestadorSemNegocioCadastradoRecebe404() throws Exception {
		String semNegocio = "Bearer " + validToken(seedUser("novo@exemplo.test", "PROVIDER", true));

		createAccount(semNegocio, body("ana@exemplo.test", "senha-inicial-123")).andExpect(status().isNotFound());

		assertEquals(0L, count("SELECT count(*) FROM users WHERE email = 'ana@exemplo.test'"));
	}

	@Test
	void atendenteNovoAindaNaoOperaAteTrocarASenhaMasConsultaOProprioPerfil() throws Exception {
		createAccount(provider, body("ana@exemplo.test", "senha-inicial-123")).andExpect(status().isCreated());
		String loginBody = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(json("email", "ana@exemplo.test", "password", "senha-inicial-123")))
				.andExpect(status().isOk()).andExpect(jsonPath("$.mustChangePassword").value(true)).andReturn().getResponse().getContentAsString();
		String attendant = "Bearer " + JsonPath.read(loginBody, "$.accessToken");

		send(get("/api/agenda").param("date", "2030-01-01"), attendant, null).andExpect(status().isForbidden()).andExpect(jsonPath("$.reason").value("PASSWORD_CHANGE_REQUIRED"));
		send(get("/api/me"), attendant, null).andExpect(status().isOk()).andExpect(jsonPath("$.mustChangePassword").value(true)).andExpect(jsonPath("$.role").value("ATTENDANT"));
	}

	@Test
	void doisCadastrosSimultaneosDoMesmoEmailCriamUmSoAtendente() throws Exception {
		ExecutorService pool = Executors.newFixedThreadPool(6);
		try {
			CyclicBarrier startTogether = new CyclicBarrier(6);
			List<Callable<Integer>> tasks = new ArrayList<>();
			for (int i = 0; i < 6; i++) {
				tasks.add(() -> {
					startTogether.await(10, TimeUnit.SECONDS);
					return createAccount(provider, body("mesmo@exemplo.test", "senha-inicial-123")).andReturn().getResponse().getStatus();
				});
			}
			List<Integer> statuses = new ArrayList<>();
			for (Future<Integer> f : pool.invokeAll(tasks, 60, TimeUnit.SECONDS)) {
				statuses.add(f.get());
			}

			assertEquals(1, statuses.stream().filter(s -> s == 201).count(), "exatamente um cria: " + statuses);
			assertEquals(5, statuses.stream().filter(s -> s == 409).count(), statuses.toString());
			assertEquals(1L, count("SELECT count(*) FROM provider_staff"));
			assertEquals(1L, count("SELECT count(*) FROM audit_log"));
		} finally {
			pool.shutdownNow();
		}
	}
}
