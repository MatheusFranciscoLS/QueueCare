package com.queuecare.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.queuecare.auth.AdminBootstrapService.BootstrapException;
import com.queuecare.auth.AdminBootstrapService.Result;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;

/** Bootstrap do primeiro ADMIN: só por comando explícito, idempotente, serializado, sem vazar entrada, sem tocar em ADMIN existente. */
class AdminBootstrapIntegrationTest extends AuthIntegrationTestBase {

	private static final String STRONG = "senha-do-admin-123";

	@Autowired
	AdminBootstrapService service;

	@Autowired
	ApplicationContext context;

	@Test
	void criaOAdminComAuditoriaESenhaEmHashEOAdminFazLogin() throws Exception {
		assertEquals(Result.CREATED, service.bootstrap("  Dono@Exemplo.TEST ", STRONG));

		Map<String, Object> admin = jdbc.queryForMap("SELECT id, role, active, must_change_password, email, password_hash FROM users");
		assertEquals("ADMIN", admin.get("role"));
		assertEquals(true, admin.get("active"));
		assertEquals("dono@exemplo.test", admin.get("email"));
		assertTrue(((String) admin.get("password_hash")).startsWith("$2") && !((String) admin.get("password_hash")).contains(STRONG));
		Map<String, Object> audit = jdbc.queryForMap("SELECT actor_user_id, action, target_user_id, target_role FROM audit_log");
		assertEquals("ADMIN_BOOTSTRAPPED", audit.get("action"));
		assertEquals(admin.get("id"), audit.get("actor_user_id"));
		assertEquals(admin.get("id"), audit.get("target_user_id"));
		assertEquals("ADMIN", audit.get("target_role"));
		mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(json("email", "dono@exemplo.test", "password", STRONG)))
				.andExpect(status().isOk()).andExpect(jsonPath("$.accessToken").exists());
	}

	@Test
	void segundaExecucaoNaoFazNadaMesmoComOutrosDadosENuncaAlteraOAdminExistente() throws Exception {
		service.bootstrap("dono@exemplo.test", STRONG);
		Map<String, Object> before = jdbc.queryForMap("SELECT id, email, password_hash, active FROM users");

		assertEquals(Result.ALREADY_EXISTS, service.bootstrap("outro@exemplo.test", "outra-senha-forte-456"));
		assertEquals(Result.ALREADY_EXISTS, service.bootstrap("dono@exemplo.test", "outra-senha-forte-456"));

		assertEquals(1L, count("SELECT count(*) FROM users"));
		assertEquals(before, jdbc.queryForMap("SELECT id, email, password_hash, active FROM users"), "e-mail, senha e estado do ADMIN existente intactos");
		assertEquals(1L, count("SELECT count(*) FROM audit_log"));
	}

	@Test
	void adminInativoNuncaEReativadoENaoSeCriaOutro() {
		service.bootstrap("dono@exemplo.test", STRONG);
		jdbc.update("UPDATE users SET active = false");

		assertEquals(Result.ALREADY_EXISTS, service.bootstrap("dono@exemplo.test", STRONG));

		assertEquals(false, jdbc.queryForObject("SELECT active FROM users", Boolean.class), "continua inativo");
		assertEquals(1L, count("SELECT count(*) FROM users"));
	}

	@Test
	void emailDeContaQueNaoEAdminImpedeOBootstrapSemAlterarAConta() {
		seedUser("dono@exemplo.test", "CUSTOMER", true);

		assertThrows(BootstrapException.class, () -> service.bootstrap("DONO@exemplo.test", STRONG));

		assertEquals("CUSTOMER", jdbc.queryForObject("SELECT role FROM users", String.class));
		assertEquals(0L, count("SELECT count(*) FROM audit_log"));
	}

	@Test
	void entradaInvalidaFalhaSemRepetirOsValoresNaMensagem() {
		List<String[]> invalid = List.of(
				new String[] {null, STRONG}, new String[] {"", STRONG}, new String[] {"dono@exemplo.test", null}, new String[] {"dono@exemplo.test", ""},
				new String[] {"sem-arroba", STRONG}, new String[] {"a@b@c.test", STRONG}, new String[] {"@exemplo.test", STRONG}, new String[] {"dono@", STRONG},
				new String[] {"do no@exemplo.test", STRONG}, new String[] {"a".repeat(250) + "@exemplo.test", STRONG},
				new String[] {"dono@exemplo.test", "onze-chars1"}, new String[] {"dono@exemplo.test", "é".repeat(40)});
		for (String[] in : invalid) {
			BootstrapException e = assertThrows(BootstrapException.class, () -> service.bootstrap(in[0], in[1]), String.valueOf(in[0]));
			String message = e.getMessage();
			assertFalse(in[0] != null && !in[0].isBlank() && message.contains(in[0].trim()), "a mensagem repete o e-mail: " + message);
			assertFalse(in[1] != null && !in[1].isEmpty() && message.contains(in[1]), "a mensagem repete a senha: " + message);
		}
		assertEquals(0L, count("SELECT count(*) FROM users"));
		assertEquals(0L, count("SELECT count(*) FROM audit_log"));
	}

	@Test
	void politicaDoAdminExigeDozeCaracteres() {
		assertThrows(BootstrapException.class, () -> service.bootstrap("dono@exemplo.test", "onze-chars1"));
		assertEquals(Result.CREATED, service.bootstrap("dono@exemplo.test", "doze-chars-12"));
	}

	@Test
	void comandosSimultaneosCriamNoMaximoUmAdmin() throws Exception {
		int threads = 8;
		ExecutorService pool = Executors.newFixedThreadPool(threads);
		try {
			CyclicBarrier startTogether = new CyclicBarrier(threads);
			List<Callable<Result>> tasks = new ArrayList<>();
			for (int i = 0; i < threads; i++) {
				int n = i;
				tasks.add(() -> {
					startTogether.await(10, TimeUnit.SECONDS);
					return service.bootstrap("admin" + n + "@exemplo.test", STRONG);
				});
			}
			List<Result> results = new ArrayList<>();
			for (Future<Result> f : pool.invokeAll(tasks, 60, TimeUnit.SECONDS)) {
				results.add(f.get());
			}

			assertEquals(1, results.stream().filter(r -> r == Result.CREATED).count(), results.toString());
			assertEquals(threads - 1, results.stream().filter(r -> r == Result.ALREADY_EXISTS).count(), results.toString());
			assertEquals(1L, count("SELECT count(*) FROM users WHERE role = 'ADMIN'"));
			assertEquals(1L, count("SELECT count(*) FROM audit_log"));
		} finally {
			pool.shutdownNow();
		}
	}

	@Test
	void ocomandoNaoEstaAtivoPorPadraoEnenhumAdminNasceSemEle() {
		assertFalse(context.containsBean("adminBootstrapRunner"), "o executor do bootstrap só existe com queuecare.bootstrap-admin.enabled=true");
		assertEquals(0L, count("SELECT count(*) FROM users WHERE role = 'ADMIN'"));
	}

	/** Com o ADMIN já criado e as variáveis removidas (como o ADR manda), executar de novo é sucesso sem mudar nada. */
	@Test
	void comandoRepetidoSemAsVariaveisOuComEntradaInvalidaTerminaComSucessoSemAlterarNada() {
		service.bootstrap("dono@exemplo.test", STRONG);
		Map<String, Object> before = jdbc.queryForMap("SELECT id, email, password_hash, active FROM users");

		for (String[] in : new String[][] {{null, null}, {"", ""}, {"dono@exemplo.test", null}, {null, STRONG}, {"nao-e-email", "curta"}}) {
			assertEquals(Result.ALREADY_EXISTS, service.bootstrap(in[0], in[1]), String.valueOf(in[0]));
		}

		assertEquals(before, jdbc.queryForMap("SELECT id, email, password_hash, active FROM users"));
		assertEquals(1L, count("SELECT count(*) FROM audit_log"));
	}

	/** O formato do e-mail no bootstrap é EXATAMENTE o da criação de prestador (mesmas anotações): aceita e recusa os mesmos endereços. */
	@Test
	void formatoDeEmailDoBootstrapEOMesmoDosOutrosFluxos() throws Exception {
		String adminBearer = null;
		List<String> addresses = List.of("a@exemplo.test", "nome.sobrenome+tag@exemplo.com.br", "a@b", "sem-arroba", "a@@b.test", "@exemplo.test", "a@",
				"a b@exemplo.test", "a@exemplo .test", "\"quoted\"@exemplo.test", "a@exemplo..test", ".a@exemplo.test", "a.@exemplo.test", "nome@[127.0.0.1]",
				"á@exemplo.test", "a".repeat(250) + "@exemplo.test", "a@" + "b".repeat(64) + ".test", "<a@exemplo.test>", "a@exemplo.test.");
		for (String address : addresses) {
			jdbc.execute("TRUNCATE appointment_events, appointments, availability_windows, services, providers, users CASCADE");
			boolean bootstrapAccepts;
			try {
				bootstrapAccepts = service.bootstrap(address, STRONG) == Result.CREATED;
			} catch (BootstrapException e) {
				bootstrapAccepts = false;
			}
			jdbc.execute("TRUNCATE appointment_events, appointments, availability_windows, services, providers, users CASCADE");
			adminBearer = "Bearer " + validToken(seedUser("admin@exemplo.test", "ADMIN", true));
			String json = "{\"name\":\"X\",\"email\":" + jsonString(address) + ",\"initialPassword\":\"senha-inicial-123\",\"businessName\":\"N\",\"timeZone\":\"America/Sao_Paulo\","
					+ "\"minimumNoticeMinutes\":0,\"cancellationDeadlineMinutes\":0}";
			int status = mvc.perform(post("/api/admin/providers").header("Authorization", adminBearer).contentType(MediaType.APPLICATION_JSON).content(json))
					.andReturn().getResponse().getStatus();
			assertEquals(status != 400, bootstrapAccepts, "bootstrap e criação de prestador discordam sobre \"" + address + "\" (status " + status + ")");
		}
	}

	private static String jsonString(String s) {
		return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
	}
}
