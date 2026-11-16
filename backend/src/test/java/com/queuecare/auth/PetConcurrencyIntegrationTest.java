package com.queuecare.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.jayway.jsonpath.JsonPath;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

/**
 * Corrida dos limites de pets (ADR 0016, item 5.2): dois pedidos simultâneos leriam a MESMA contagem e passariam do limite se o
 * protocolo "bloquear o cliente, contar, gravar" não estivesse valendo para toda operação que torna um pet ativo (criar e reativar).
 * O PostgreSQL é real e as requisições entram pela API, em threads de verdade, largadas ao mesmo tempo.
 */
class PetConcurrencyIntegrationTest extends AuthIntegrationTestBase {

	UUID owner;
	String token;

	@BeforeEach
	void seed() throws Exception {
		owner = seedUser("tutor-c@exemplo.test", "CUSTOMER", true);
		String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(json("email", "tutor-c@exemplo.test", "password", PASSWORD)))
				.andReturn().getResponse().getContentAsString();
		token = "Bearer " + JsonPath.read(body, "$.accessToken");
	}

	/** Resultado de uma chamada: status HTTP e, nos erros, o reason. */
	record Outcome(int status, String reason) {
	}

	private void seedPets(int active, int archived) {
		for (int i = 0; i < active + archived; i++) {
			jdbc.update("INSERT INTO pets (customer_id, name, species, active) VALUES (?, ?, 'DOG', ?)", owner, "Pet " + i, i < active);
		}
	}

	private Outcome call(String url, String content) throws Exception {
		var request = post(url).header(HttpHeaders.AUTHORIZATION, token);
		if (content != null) {
			request = request.contentType(MediaType.APPLICATION_JSON).content(content);
		}
		var response = mvc.perform(request).andReturn().getResponse();
		String reason = null;
		if (response.getStatus() >= 400) {
			reason = JsonPath.read(response.getContentAsString(), "$.reason");
		}
		return new Outcome(response.getStatus(), reason);
	}

	/** Larga todas as chamadas ao mesmo tempo (uma trava de partida) e devolve os resultados. */
	private List<Outcome> fireTogether(List<Callable<Outcome>> calls) throws Exception {
		ExecutorService pool = Executors.newFixedThreadPool(calls.size());
		try {
			CountDownLatch ready = new CountDownLatch(calls.size());
			CountDownLatch go = new CountDownLatch(1);
			List<Future<Outcome>> futures = new ArrayList<>();
			for (Callable<Outcome> call : calls) {
				futures.add(pool.submit(() -> {
					ready.countDown();
					go.await();
					return call.call();
				}));
			}
			assertTrue(ready.await(10, TimeUnit.SECONDS));
			go.countDown();
			List<Outcome> outcomes = new ArrayList<>();
			for (Future<Outcome> f : futures) {
				outcomes.add(f.get(60, TimeUnit.SECONDS));
			}
			return outcomes;
		} finally {
			pool.shutdownNow();
		}
	}

	private static long countStatus(List<Outcome> outcomes, int status) {
		return outcomes.stream().filter(o -> o.status() == status).count();
	}

	/** Todo 409 tem de ser o limite (ou, no máximo, a espera de bloqueio estourada), nunca um erro de verdade. */
	private static void assertOnlyExpectedRefusals(List<Outcome> outcomes) {
		for (Outcome o : outcomes) {
			assertTrue(o.status() == 201 || o.status() == 200 || (o.status() == 409 && ("PET_LIMIT_REACHED".equals(o.reason()) || "RESOURCE_BUSY".equals(o.reason()))),
					"resposta inesperada: " + o);
		}
	}

	@Test
	void trintaCriacoesSimultaneasTerminamComExatamenteVintePets() throws Exception {
		List<Callable<Outcome>> calls = new ArrayList<>();
		for (int i = 0; i < 30; i++) {
			String content = PetApiIntegrationTest.simple("Pet " + i);
			calls.add(() -> call("/api/customer/pets", content));
		}
		List<Outcome> outcomes = fireTogether(calls);
		assertOnlyExpectedRefusals(outcomes);
		assertEquals(20L, count("SELECT count(*) FROM pets WHERE customer_id = ? AND active", owner), "o banco nunca passa de 20 ativos");
		assertEquals(20L, countStatus(outcomes, 201));
	}

	@Test
	void doisPedidosPeloVigesimoLugarSoUmPassa() throws Exception {
		seedPets(19, 0);
		List<Callable<Outcome>> calls = new ArrayList<>();
		for (int i = 0; i < 2; i++) {
			String content = PetApiIntegrationTest.simple("Disputa " + i);
			calls.add(() -> call("/api/customer/pets", content));
		}
		List<Outcome> outcomes = fireTogether(calls);
		assertOnlyExpectedRefusals(outcomes);
		assertEquals(20L, count("SELECT count(*) FROM pets WHERE customer_id = ? AND active", owner));
		assertEquals(1L, countStatus(outcomes, 201));
	}

	@Test
	void criarEReativarSimultaneosDividemAsMesmasVagas() throws Exception {
		seedPets(15, 10); // 5 vagas para 10 criações e 10 reativações ao mesmo tempo
		List<String> archived = jdbc.queryForList("SELECT id::text FROM pets WHERE customer_id = ? AND NOT active", String.class, owner);
		List<Callable<Outcome>> calls = new ArrayList<>();
		for (int i = 0; i < 10; i++) {
			String content = PetApiIntegrationTest.simple("Novo " + i);
			calls.add(() -> call("/api/customer/pets", content));
			String id = archived.get(i);
			calls.add(() -> call("/api/customer/pets/" + id + "/reactivate", null));
		}
		List<Outcome> outcomes = fireTogether(calls);
		assertOnlyExpectedRefusals(outcomes);
		assertEquals(20L, count("SELECT count(*) FROM pets WHERE customer_id = ? AND active", owner), "criar e reativar compartilham o mesmo limite");
		assertEquals(5L, outcomes.stream().filter(o -> o.status() == 200 || o.status() == 201).count());
	}
}
