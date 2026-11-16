package com.queuecare.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Página pública do estúdio (ADR 0015): leitura SEM login que só mostra o que foi publicado de propósito, com a mesma ausência para
 * endereço inexistente, inválido, não publicado ou de dono inativo; e a configuração do endereço pelo dono, com unicidade garantida
 * pelo banco mesmo sob concorrência. PostgreSQL real, filtros reais.
 */
class PublicStudioApiIntegrationTest extends AuthIntegrationTestBase {

	UUID ownerA, ownerB, attendant, customer;
	UUID providerA, providerB;
	String tokenA, tokenB, tokenAttendant, tokenCustomer;

	@BeforeEach
	void seed() throws Exception {
		ownerA = seedUser("pa@exemplo.test", "PROVIDER", true);
		ownerB = seedUser("pb@exemplo.test", "PROVIDER", true);
		attendant = seedUser("at@exemplo.test", "ATTENDANT", true);
		customer = seedUser("cl@exemplo.test", "CUSTOMER", true);
		providerA = business(ownerA, "Estúdio Alfa");
		providerB = business(ownerB, "Estúdio Beta");
		tokenA = tokenOf("pa@exemplo.test");
		tokenB = tokenOf("pb@exemplo.test");
		tokenAttendant = tokenOf("at@exemplo.test");
		tokenCustomer = tokenOf("cl@exemplo.test");
	}

	private UUID business(UUID owner, String name) {
		return jdbc.queryForObject("INSERT INTO providers (user_id, business_name, time_zone, minimum_notice_minutes, cancellation_deadline_minutes) "
				+ "VALUES (?, ?, 'America/Sao_Paulo', 0, 120) RETURNING id", UUID.class, owner, name);
	}

	private String tokenOf(String email) throws Exception {
		String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(json("email", email, "password", PASSWORD)))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		return "Bearer " + JsonPath.read(body, "$.accessToken");
	}

	private ResultActions setPage(String bearer, String slug, boolean published) throws Exception {
		String body = slug == null ? "{\"slug\":null,\"published\":" + published + "}" : "{\"slug\":\"" + slug + "\",\"published\":" + published + "}";
		return mvc.perform(put("/api/provider/public-page").header(HttpHeaders.AUTHORIZATION, bearer).contentType(MediaType.APPLICATION_JSON).content(body));
	}

	private ResultActions studio(String slug) throws Exception {
		return mvc.perform(get("/api/public/studios/" + slug));
	}

	private String bodyOfNotFound(String slug) throws Exception {
		return withoutInstance(studio(slug).andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString());
	}

	/** O campo instance repete o caminho pedido pelo próprio visitante; o resto da resposta é o que não pode diferenciar os casos. */
	private static String withoutInstance(String body) {
		return body.replaceAll("\"instance\":\"[^\"]*\",?", "");
	}

	private void service(UUID provider, String name, int minutes, boolean active) {
		jdbc.update("INSERT INTO services (provider_id, name, duration_minutes, active) VALUES (?, ?, ?, ?)", provider, name, minutes, active);
	}

	private static Set<String> fieldNames(JsonNode node) {
		Set<String> names = new HashSet<>();
		node.fieldNames().forEachRemaining(names::add);
		return names;
	}

	@Test
	void estudioPublicadoApareceSemLoginComSoOQueFoiPublicado() throws Exception {
		service(providerA, "Banho", 60, true);
		service(providerA, "Tosa", 90, true);
		service(providerA, "Serviço desativado", 30, false);
		setPage(tokenA, "estudio-alfa", true).andExpect(status().isOk()).andExpect(jsonPath("$.slug").value("estudio-alfa")).andExpect(jsonPath("$.published").value(true));

		String body = studio("estudio-alfa").andExpect(status().isOk())
				.andExpect(jsonPath("$.businessName").value("Estúdio Alfa"))
				.andExpect(jsonPath("$.timeZone").value("America/Sao_Paulo"))
				.andExpect(jsonPath("$.services.length()").value(2))
				.andExpect(jsonPath("$.services[0].name").value("Banho"))
				.andExpect(jsonPath("$.services[0].durationMinutes").value(60))
				.andReturn().getResponse().getContentAsString();
		JsonNode json = new ObjectMapper().readTree(body);
		// Só estes campos: nada de ids, e-mail, equipe nem configurações.
		assertEquals(Set.of("slug", "businessName", "timeZone", "services"), fieldNames(json));
		assertEquals(Set.of("name", "durationMinutes"), fieldNames(json.get("services").get(0)));
		assertTrue(!body.contains("pa@exemplo.test") && !body.contains(providerA.toString()) && !body.contains("Serviço desativado"));
	}

	@Test
	void inexistenteNaoPublicadoInvalidoEDonoInativoTemExatamenteAMesmaResposta() throws Exception {
		setPage(tokenA, "estudio-alfa", false).andExpect(status().isOk()); // tem endereço, mas NÃO publicado
		String missing = studio("nao-existe").andExpect(status().isNotFound()).andExpect(jsonPath("$.reason").value("STUDIO_NOT_FOUND")).andReturn()
				.getResponse().getContentAsString();
		missing = withoutInstance(missing);
		assertEquals(missing, bodyOfNotFound("estudio-alfa"));
		assertEquals(missing, bodyOfNotFound("AB")); // curto demais
		assertEquals(missing, bodyOfNotFound("Estudio-Alfa")); // maiúsculas
		assertEquals(missing, bodyOfNotFound("a".repeat(41)));

		setPage(tokenA, "estudio-alfa", true).andExpect(status().isOk());
		studio("estudio-alfa").andExpect(status().isOk());
		jdbc.update("UPDATE users SET active = false WHERE id = ?", ownerA); // dono desativado: a página some
		assertEquals(missing, bodyOfNotFound("estudio-alfa"));
	}

	@Test
	void validacaoDoEndereco() throws Exception {
		for (String bad : List.of("ab", "Maiusculas", "com espaco", "-comeca", "termina-", "duplo--hifen", "a".repeat(41), "acentuação", "sub_linha")) {
			setPage(tokenA, bad, true).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.slug").exists());
		}
		for (String reserved : List.of("admin", "api", "entrar", "pelo-e-ponto", "agenda")) {
			setPage(tokenA, reserved, true).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.slug").value("endereço reservado"));
		}
		setPage(tokenA, null, true).andExpect(status().isBadRequest()); // publicar exige endereço
		setPage(tokenA, "  ", true).andExpect(status().isBadRequest());
		assertEquals(0L, count("SELECT count(*) FROM providers WHERE slug IS NOT NULL"), "nada foi gravado pelas tentativas inválidas");
		setPage(tokenA, null, false).andExpect(status().isOk()).andExpect(jsonPath("$.slug").doesNotExist()); // despublicar e limpar é permitido
	}

	@Test
	void enderecoEUnicoOutroEstudioRecebeConflitoEOMesmoDonoPodeRegravar() throws Exception {
		setPage(tokenA, "estudio-alfa", true).andExpect(status().isOk());
		setPage(tokenA, "estudio-alfa", true).andExpect(status().isOk()); // regravar o PRÓPRIO endereço
		setPage(tokenB, "estudio-alfa", true).andExpect(status().isConflict()).andExpect(jsonPath("$.reason").value("SLUG_TAKEN"));
		assertEquals(0L, count("SELECT count(*) FROM providers WHERE id = '" + providerB + "' AND slug IS NOT NULL"), "o conflito não gravou nada no outro estúdio");
		setPage(tokenA, "alfa-novo", true).andExpect(status().isOk()); // trocar libera o antigo
		setPage(tokenB, "estudio-alfa", true).andExpect(status().isOk());
		studio("estudio-alfa").andExpect(status().isOk()).andExpect(jsonPath("$.businessName").value("Estúdio Beta"));
	}

	@Test
	void doisEstudiosDisputandoOMesmoEnderecoAoMesmoTempoUmSoVence() throws Exception {
		CyclicBarrier barrier = new CyclicBarrier(2);
		List<Callable<Integer>> tasks = new ArrayList<>();
		for (String token : List.of(tokenA, tokenB)) {
			tasks.add(() -> {
				barrier.await(10, TimeUnit.SECONDS);
				return setPage(token, "disputado", true).andReturn().getResponse().getStatus();
			});
		}
		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			List<Integer> statuses = new ArrayList<>();
			for (Future<Integer> f : pool.invokeAll(tasks, 60, TimeUnit.SECONDS)) {
				statuses.add(f.get());
			}
			statuses.sort(Integer::compare);
			assertEquals(List.of(200, 409), statuses);
		} finally {
			pool.shutdownNow();
		}
		assertEquals(1L, count("SELECT count(*) FROM providers WHERE slug = 'disputado'"));
	}

	@Test
	void soODonoConfiguraAPaginaESemTokenNaoEntra() throws Exception {
		setPage(tokenCustomer, "x-y-z", true).andExpect(status().isForbidden());
		setPage(tokenAttendant, "x-y-z", true).andExpect(status().isForbidden());
		mvc.perform(put("/api/provider/public-page").contentType(MediaType.APPLICATION_JSON).content("{\"slug\":\"x-y-z\",\"published\":true}")).andExpect(status().isUnauthorized());
		mvc.perform(get("/api/provider/public-page")).andExpect(status().isUnauthorized());
		mvc.perform(get("/api/provider/public-page").header(HttpHeaders.AUTHORIZATION, tokenCustomer)).andExpect(status().isForbidden());
		mvc.perform(get("/api/provider/public-page").header(HttpHeaders.AUTHORIZATION, tokenA)).andExpect(status().isOk()).andExpect(jsonPath("$.published").value(false));
	}

	@Test
	void paginaPublicaSoPermiteLeituraENaoAceitaEscritaSemLogin() throws Exception {
		mvc.perform(post("/api/public/studios/estudio-alfa")).andExpect(status().isUnauthorized());
		mvc.perform(put("/api/public/studios/estudio-alfa")).andExpect(status().isUnauthorized());
	}

	@Test
	void prestadorSemNegocioNaoConfiguraPagina() throws Exception {
		seedUser("semneg@exemplo.test", "PROVIDER", true);
		setPage(tokenOf("semneg@exemplo.test"), "algum-nome", true).andExpect(status().isNotFound()).andExpect(jsonPath("$.reason").value("PROVIDER_NOT_CONFIGURED"));
	}

	@Test
	void bancoRecusaPublicarSemEnderecoEFormatoInvalido() {
		assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("UPDATE providers SET published = true WHERE id = ?", providerA));
		assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("UPDATE providers SET slug = 'Ruim--Formato' WHERE id = ?", providerA));
	}
}
