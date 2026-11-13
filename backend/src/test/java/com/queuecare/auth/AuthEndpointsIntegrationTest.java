package com.queuecare.auth;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.ResultActions;

/** Cadastro e login pela API real (MockMvc + filtros do Spring Security + PostgreSQL real). */
class AuthEndpointsIntegrationTest extends AuthIntegrationTestBase {

	@Autowired
	JwtDecoder jwtDecoder;

	private ResultActions register(String name, String email, String password) throws Exception {
		return mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(json("name", name, "email", email, "password", password)));
	}

	private ResultActions login(String email, String password) throws Exception {
		return mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(json("email", email, "password", password)));
	}

	// --- cadastro ---

	@Test
	void cadastroCriaClienteSemExporHashNemSenha() throws Exception {
		String body = register("Maria Souza", "maria@exemplo.test", PASSWORD)
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.role").value("CUSTOMER"))
				.andExpect(jsonPath("$.email").value("maria@exemplo.test"))
				.andExpect(jsonPath("$.id").isNotEmpty())
				.andExpect(jsonPath("$.passwordHash").doesNotExist())
				.andExpect(jsonPath("$.password").doesNotExist())
				.andExpect(content().string(not(containsString("$2"))))
				.andReturn().getResponse().getContentAsString();

		String hash = jdbc.queryForObject("SELECT password_hash FROM users WHERE email = 'maria@exemplo.test'", String.class);
		assertTrue(hash.startsWith("$2"), "a senha deve estar em BCrypt");
		assertTrue(!hash.contains(PASSWORD));
		assertTrue(!body.contains(PASSWORD));
	}

	@Test
	void cadastroIgnoraPerfilEnviadoPeloCliente() throws Exception {
		mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\":\"Mallory\",\"email\":\"mallory@exemplo.test\",\"password\":\"" + PASSWORD + "\",\"role\":\"PROVIDER\"}"))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.role").value("CUSTOMER"));

		assertEquals("CUSTOMER", jdbc.queryForObject("SELECT role FROM users WHERE email = 'mallory@exemplo.test'", String.class));
	}

	@Test
	void cadastroNormalizaOEmail() throws Exception {
		register("Ana", "  Ana.Lima@Exemplo.TEST ", PASSWORD).andExpect(status().isCreated()).andExpect(jsonPath("$.email").value("ana.lima@exemplo.test"));

		assertEquals(1L, jdbc.queryForObject("SELECT count(*) FROM users WHERE email = 'ana.lima@exemplo.test'", Long.class));
	}

	@Test
	void emailDuplicadoEhConflitoSemDiferenciarMaiusculasNemEspacos() throws Exception {
		register("Ana", "ana@exemplo.test", PASSWORD).andExpect(status().isCreated());

		register("Outra Ana", " ANA@exemplo.test", PASSWORD).andExpect(status().isConflict());
		assertEquals(1L, jdbc.queryForObject("SELECT count(*) FROM users", Long.class));
	}

	@Test
	void cadastrosSimultaneosDoMesmoEmailApenasUmVence() throws Exception {
		ExecutorService pool = Executors.newFixedThreadPool(4);
		CyclicBarrier startTogether = new CyclicBarrier(4);
		try {
			List<Callable<Integer>> tasks = new ArrayList<>();
			for (int i = 0; i < 4; i++) {
				tasks.add(() -> {
					startTogether.await(10, TimeUnit.SECONDS);
					return register("Concorrente", "concorrente@exemplo.test", PASSWORD).andReturn().getResponse().getStatus();
				});
			}
			List<Integer> statuses = new ArrayList<>();
			for (Future<Integer> future : pool.invokeAll(tasks, 60, TimeUnit.SECONDS)) {
				statuses.add(future.get());
			}
			assertEquals(1, statuses.stream().filter(s -> s == 201).count(), "exatamente um cadastro vence: " + statuses);
			assertTrue(statuses.stream().filter(s -> s != 201).allMatch(s -> s == 409), "os demais recebem 409 e nenhum 500: " + statuses);
		} finally {
			pool.shutdownNow();
		}
		assertEquals(1L, jdbc.queryForObject("SELECT count(*) FROM users", Long.class));
	}

	@Test
	void dadosInvalidosNoCadastroSaoRecusadosSemRepetirOsValores() throws Exception {
		register("Fulano", "isto-nao-eh-email", PASSWORD).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.email").exists());
		register("", "ok@exemplo.test", PASSWORD).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.name").exists());
		register("Fulano", "ok@exemplo.test", "curta").andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.password").exists())
				.andExpect(content().string(not(containsString("curta"))));
		assertEquals(0L, jdbc.queryForObject("SELECT count(*) FROM users", Long.class));
	}

	@Test
	void senhaAcimaDe72BytesEmUtf8EhRecusadaEmVezDeTruncada() throws Exception {
		register("Fulano", "a@exemplo.test", "a".repeat(73)).andExpect(status().isBadRequest());
		register("Fulano", "b@exemplo.test", "é".repeat(37)).andExpect(status().isBadRequest()); // 74 bytes
		register("Fulano", "c@exemplo.test", "a".repeat(72)).andExpect(status().isCreated()); // exatamente 72 bytes
		register("Fulano", "d@exemplo.test", "é".repeat(36)).andExpect(status().isCreated()); // 72 bytes
	}

	@Test
	void corpoMalFormadoEh400() throws Exception {
		mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content("{isto não é json")).andExpect(status().isBadRequest());
	}

	// --- login ---

	@Test
	void loginDevolveTokenValidoComOsClaimsEsperados() throws Exception {
		register("Maria", "maria@exemplo.test", PASSWORD).andExpect(status().isCreated());

		String body = login("MARIA@exemplo.test ", PASSWORD).andExpect(status().isOk())
				.andExpect(jsonPath("$.tokenType").value("Bearer"))
				.andExpect(jsonPath("$.expiresIn").value(900))
				.andReturn().getResponse().getContentAsString();

		Jwt jwt = jwtDecoder.decode(JsonPath.read(body, "$.accessToken"));
		UUID id = jdbc.queryForObject("SELECT id FROM users WHERE email = 'maria@exemplo.test'", UUID.class);
		assertEquals(id.toString(), jwt.getSubject());
		assertEquals(ISSUER, jwt.getClaimAsString("iss"));
		assertEquals(List.of(AUDIENCE), jwt.getAudience());
		assertEquals("CUSTOMER", jwt.getClaimAsString("role"));
		assertEquals(900, jwt.getExpiresAt().getEpochSecond() - jwt.getIssuedAt().getEpochSecond());
		assertEquals("HS256", jwt.getHeaders().get("alg"));
		assertTrue(jwt.getClaims().keySet().stream().noneMatch(k -> k.toLowerCase().contains("pass") || k.equals("email")),
				"o token não deve carregar senha nem dados pessoais: " + jwt.getClaims().keySet());
	}

	@Test
	void senhaErradaEEmailInexistenteDaoExatamenteAMesmaResposta() throws Exception {
		register("Maria", "maria@exemplo.test", PASSWORD).andExpect(status().isCreated());

		String wrongPassword = login("maria@exemplo.test", "senha-errada-123").andExpect(status().isUnauthorized()).andReturn().getResponse().getContentAsString();
		String unknownEmail = login("ninguem@exemplo.test", PASSWORD).andExpect(status().isUnauthorized()).andReturn().getResponse().getContentAsString();

		assertEquals(wrongPassword, unknownEmail, "a resposta não pode revelar se o e-mail existe");
		assertTrue(wrongPassword.contains("credenciais inválidas"));
	}

	@Test
	void usuarioInativoNaoFazLogin() throws Exception {
		seedUser("inativo@exemplo.test", "CUSTOMER", false);

		login("inativo@exemplo.test", PASSWORD).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.detail").value("credenciais inválidas"));
	}

	@Test
	void senhaGiganteNoLoginEhRecusadaSemErro500() throws Exception {
		seedUser("maria@exemplo.test", "CUSTOMER", true);

		login("maria@exemplo.test", "a".repeat(5000)).andExpect(status().isUnauthorized());
	}

	@Test
	void loginComCorpoIncompletoEh400() throws Exception {
		mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"maria@exemplo.test\"}")).andExpect(status().isBadRequest());
	}

	@Test
	void tokenEmitidoEstaDentroDoPrazoEExpiraEm15Minutos() throws Exception {
		seedUser("maria@exemplo.test", "CUSTOMER", true);
		Instant before = Instant.now().minusSeconds(2);

		String body = login("maria@exemplo.test", PASSWORD).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

		Jwt jwt = jwtDecoder.decode(JsonPath.read(body, "$.accessToken"));
		assertTrue(jwt.getIssuedAt().isAfter(before));
		assertTrue(jwt.getExpiresAt().isAfter(Instant.now().plusSeconds(800)));
		assertTrue(jwt.getExpiresAt().isBefore(Instant.now().plusSeconds(901)));
	}
}
