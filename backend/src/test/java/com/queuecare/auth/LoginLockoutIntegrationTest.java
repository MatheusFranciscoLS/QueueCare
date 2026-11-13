package com.queuecare.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** Bloqueio temporário por falhas de login (e-mail + endereço), com limite baixo só neste teste. */
@TestPropertySource(properties = {
		"queuecare.rate-limit.login-per-minute=100000",
		"queuecare.rate-limit.login-failures-per-email-and-ip=3"
})
class LoginLockoutIntegrationTest extends AuthIntegrationTestBase {

	private static RequestPostProcessor from(String address) {
		return request -> {
			request.setRemoteAddr(address);
			return request;
		};
	}

	private int login(String email, String password, String address) throws Exception {
		return mvc.perform(post("/api/auth/login").with(from(address)).contentType(MediaType.APPLICATION_JSON).content(json("email", email, "password", password)))
				.andReturn().getResponse().getStatus();
	}

	@Test
	void depoisDeNFalhasOParBloqueiaMesmoComASenhaCerta() throws Exception {
		seedUser("maria@exemplo.test", "CUSTOMER", true);

		assertEquals(401, login("maria@exemplo.test", "errada-0001", "10.0.0.1"));
		assertEquals(401, login("maria@exemplo.test", "errada-0002", "10.0.0.1"));
		assertEquals(401, login("maria@exemplo.test", "errada-0003", "10.0.0.1"));

		assertEquals(429, login("maria@exemplo.test", PASSWORD, "10.0.0.1"), "bloqueado mesmo com a senha certa");
		mvc.perform(post("/api/auth/login").with(from("10.0.0.1")).contentType(MediaType.APPLICATION_JSON).content(json("email", "maria@exemplo.test", "password", PASSWORD)))
				.andExpect(status().isTooManyRequests()).andExpect(header().exists(HttpHeaders.RETRY_AFTER));
	}

	@Test
	void obloqueioNaoTrancaOUsuarioEmOutroEnderecoNemOutrasContas() throws Exception {
		seedUser("maria@exemplo.test", "CUSTOMER", true);
		seedUser("joao@exemplo.test", "CUSTOMER", true);
		for (int i = 0; i < 3; i++) {
			login("maria@exemplo.test", "errada-000" + i, "10.0.0.66"); // o atacante
		}

		assertEquals(429, login("maria@exemplo.test", PASSWORD, "10.0.0.66"));
		assertEquals(200, login("maria@exemplo.test", PASSWORD, "10.0.0.2"), "a dona, em outro endereço, entra normalmente");
		assertEquals(200, login("joao@exemplo.test", PASSWORD, "10.0.0.66"), "outra conta no mesmo endereço não é afetada");
	}

	@Test
	void emailInexistenteSeComportaExatamenteComoExistenteSemRevelarContas() throws Exception {
		seedUser("maria@exemplo.test", "CUSTOMER", true);
		List<Integer> existing = new ArrayList<>();
		List<Integer> missing = new ArrayList<>();
		for (int i = 0; i < 4; i++) {
			existing.add(login("maria@exemplo.test", "errada-000" + i, "10.0.0.1"));
			missing.add(login("ninguem@exemplo.test", "errada-000" + i, "10.0.0.1"));
		}

		assertEquals(List.of(401, 401, 401, 429), existing);
		assertEquals(existing, missing, "a sequência de respostas é idêntica para e-mail existente e inexistente");
	}

	@Test
	void usuarioInativoTambemContaECasoSeguinteBloqueia() throws Exception {
		seedUser("inativo@exemplo.test", "CUSTOMER", false);
		List<Integer> statuses = new ArrayList<>();
		for (int i = 0; i < 4; i++) {
			statuses.add(login("inativo@exemplo.test", PASSWORD, "10.0.0.1"));
		}

		assertEquals(List.of(401, 401, 401, 429), statuses);
	}

	@Test
	void loginComSucessoZeraAContagemDeFalhas() throws Exception {
		seedUser("maria@exemplo.test", "CUSTOMER", true);

		assertEquals(401, login("maria@exemplo.test", "errada-0001", "10.0.0.1"));
		assertEquals(401, login("maria@exemplo.test", "errada-0002", "10.0.0.1"));
		assertEquals(200, login("maria@exemplo.test", PASSWORD, "10.0.0.1"));
		assertEquals(401, login("maria@exemplo.test", "errada-0003", "10.0.0.1"));
		assertEquals(401, login("maria@exemplo.test", "errada-0004", "10.0.0.1"));
		assertEquals(200, login("maria@exemplo.test", PASSWORD, "10.0.0.1"), "duas falhas depois do sucesso ainda não bloqueiam");
	}

	@Test
	void emailComEspacosEMaiusculasContaComoOMesmo() throws Exception {
		seedUser("maria@exemplo.test", "CUSTOMER", true);

		assertEquals(401, login("maria@exemplo.test", "errada-0001", "10.0.0.1"));
		assertEquals(401, login(" MARIA@exemplo.test ", "errada-0002", "10.0.0.1"));
		assertEquals(401, login("Maria@Exemplo.test", "errada-0003", "10.0.0.1"));

		assertEquals(429, login("maria@exemplo.test", PASSWORD, "10.0.0.1"), "variações do mesmo e-mail não burlam o bloqueio");
		assertTrue(login("outro@exemplo.test", PASSWORD, "10.0.0.1") != 429);
	}

	/** Conferir-e-depois-registrar deixaria passar tentativas em paralelo; a reserva atômica não deixa. */
	@Test
	void tentativasSimultaneasNaoPassamDoLimiteDeConferenciasDeSenha() throws Exception {
		seedUser("maria@exemplo.test", "CUSTOMER", true);
		int threads = 40;
		ExecutorService pool = Executors.newFixedThreadPool(threads);
		try {
			CyclicBarrier startTogether = new CyclicBarrier(threads);
			List<Callable<Integer>> tasks = new ArrayList<>();
			for (int i = 0; i < threads; i++) {
				int n = i;
				tasks.add(() -> {
					startTogether.await(10, TimeUnit.SECONDS);
					return login("maria@exemplo.test", "errada-" + n, "10.0.0.1");
				});
			}
			List<Integer> statuses = new ArrayList<>();
			for (Future<Integer> f : pool.invokeAll(tasks, 60, TimeUnit.SECONDS)) {
				statuses.add(f.get());
			}

			assertEquals(3, statuses.stream().filter(s -> s == 401).count(), "só 3 senhas chegam a ser conferidas: " + statuses);
			assertEquals(threads - 3, statuses.stream().filter(s -> s == 429).count(), "as demais são barradas: " + statuses);
			assertEquals(429, login("maria@exemplo.test", PASSWORD, "10.0.0.1"), "e a senha certa também fica bloqueada");
		} finally {
			pool.shutdownNow();
		}
	}
}
