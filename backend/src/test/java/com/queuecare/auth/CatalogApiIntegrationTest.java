package com.queuecare.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletResponse;

/** Catálogo do cliente: só o mínimo público, só ativos, só CUSTOMER; e histórico do cliente com nomes. */
class CatalogApiIntegrationTest extends AuthIntegrationTestBase {

	private String customer;
	private UUID ownerB;
	private UUID providerA;
	private UUID providerB;
	private UUID providerC;

	private UUID provider(String email, String businessName, String zone) {
		UUID owner = seedUser(email, "PROVIDER", true);
		jdbc.update("INSERT INTO providers (user_id, business_name, time_zone) VALUES (?, ?, ?)", owner, businessName, zone);
		return jdbc.queryForObject("SELECT id FROM providers WHERE user_id = ?", UUID.class, owner);
	}

	private UUID service(UUID providerId, String name, int minutes, boolean active) {
		return jdbc.queryForObject("INSERT INTO services (provider_id, name, duration_minutes, active) VALUES (?, ?, ?, ?) RETURNING id", UUID.class, providerId, name, minutes, active);
	}

	@BeforeEach
	void seed() {
		customer = "Bearer " + validToken(seedUser("cliente@exemplo.test", "CUSTOMER", true));
		providerB = provider("b@exemplo.test", "barbearia do Zé", "America/Sao_Paulo");
		providerA = provider("a@exemplo.test", "Auto Center Alfa", "America/Manaus");
		providerC = provider("c@exemplo.test", "Clínica Beta", "America/Sao_Paulo");
		ownerB = jdbc.queryForObject("SELECT user_id FROM providers WHERE id = ?", UUID.class, providerB);
		service(providerA, "Troca de óleo", 60, true);
		service(providerA, "Alinhamento", 30, true);
		service(providerA, "Serviço antigo", 45, false);
	}

	private MockHttpServletResponse call(String path, String bearer) throws Exception {
		var request = get(path);
		if (bearer != null) {
			request = request.header(HttpHeaders.AUTHORIZATION, bearer);
		}
		return mvc.perform(request).andReturn().getResponse();
	}

	@Test
	void listaPrestadoresAtivosEmOrdemEstavelSemDiferenciarMaiusculas() throws Exception {
		MockHttpServletResponse r = call("/api/catalog/providers", customer);

		assertEquals(200, r.getStatus());
		assertEquals(List.of("Auto Center Alfa", "barbearia do Zé", "Clínica Beta"), JsonPath.<List<String>>read(r.getContentAsString(), "$[*].businessName"));
	}

	@Test
	void devolveSoOMinimoPublicoDeCadaPrestador() throws Exception {
		String body = call("/api/catalog/providers", customer).getContentAsString();

		for (Map<String, Object> item : JsonPath.<List<Map<String, Object>>>read(body, "$")) {
			assertEquals(Set.of("id", "businessName", "timeZone"), item.keySet(), "só id, nome do negócio e fuso");
		}
		assertFalse(body.contains("@exemplo.test") || body.contains("userId") || body.contains("minimumNotice") || body.contains("cancellationDeadline"), body);
	}

	@Test
	void prestadorComDonoDesativadoNaoApareceNemPorIdDireto() throws Exception {
		jdbc.update("UPDATE users SET active = false WHERE id = ?", ownerB);

		List<String> names = JsonPath.read(call("/api/catalog/providers", customer).getContentAsString(), "$[*].businessName");
		assertEquals(List.of("Auto Center Alfa", "Clínica Beta"), names);
		MockHttpServletResponse direct = call("/api/catalog/providers/" + providerB + "/services", customer);
		MockHttpServletResponse unknown = call("/api/catalog/providers/" + UUID.randomUUID() + "/services", customer);
		assertEquals(404, direct.getStatus());
		assertEquals(404, unknown.getStatus());
		assertEquals(unknown.getContentAsString().replaceAll("\"instance\":\"[^\"]*\",?", ""), direct.getContentAsString().replaceAll("\"instance\":\"[^\"]*\",?", ""),
				"desativado e inexistente respondem igual");
	}

	@Test
	void paginacaoEstavelELimiteDeTamanho() throws Exception {
		assertEquals(List.of("Auto Center Alfa", "barbearia do Zé"), JsonPath.read(call("/api/catalog/providers?page=0&size=2", customer).getContentAsString(), "$[*].businessName"));
		assertEquals(List.of("Clínica Beta"), JsonPath.read(call("/api/catalog/providers?page=1&size=2", customer).getContentAsString(), "$[*].businessName"));
		assertEquals(0, ((List<?>) JsonPath.read(call("/api/catalog/providers?page=5&size=2", customer).getContentAsString(), "$")).size());
		assertEquals(400, call("/api/catalog/providers?size=51", customer).getStatus());
		assertEquals(400, call("/api/catalog/providers?size=0", customer).getStatus());
		assertEquals(400, call("/api/catalog/providers?page=-1", customer).getStatus());
	}

	@Test
	void servicosSoOsAtivosComIdNomeEDuracao() throws Exception {
		String body = call("/api/catalog/providers/" + providerA + "/services", customer).getContentAsString();

		assertEquals(List.of("Alinhamento", "Troca de óleo"), JsonPath.<List<String>>read(body, "$[*].name"));
		for (Map<String, Object> item : JsonPath.<List<Map<String, Object>>>read(body, "$")) {
			assertEquals(Set.of("id", "name", "durationMinutes"), item.keySet());
		}
		assertFalse(body.contains("Serviço antigo"), "serviço desativado nunca aparece");
		assertEquals("[]", call("/api/catalog/providers/" + providerC + "/services", customer).getContentAsString(), "prestador sem serviços: lista vazia");
	}

	@Test
	void soClienteLeEOsOutrosPerfisEAnonimoNao() throws Exception {
		for (String role : List.of("PROVIDER", "ATTENDANT", "ADMIN")) {
			String bearer = "Bearer " + validToken(seedUser(role.toLowerCase() + "-x@exemplo.test", role, true));
			assertEquals(403, call("/api/catalog/providers", bearer).getStatus(), role);
			assertEquals(403, call("/api/catalog/providers/" + providerA + "/services", bearer).getStatus(), role);
		}
		assertEquals(401, call("/api/catalog/providers", null).getStatus());
		assertEquals(401, call("/api/catalog/providers/" + providerA + "/services", null).getStatus());
		String pending = "Bearer " + validToken(seedUser("pendente@exemplo.test", "CUSTOMER", true, true));
		MockHttpServletResponse r = call("/api/catalog/providers", pending);
		assertEquals(403, r.getStatus());
		assertTrue(r.getContentAsString().contains("PASSWORD_CHANGE_REQUIRED"));
		String inactive = "Bearer " + validToken(seedUser("inativo@exemplo.test", "CUSTOMER", false));
		assertEquals(401, call("/api/catalog/providers", inactive).getStatus());
	}

	@Test
	void idInvalidoNaRotaDeServicosEhErroDeEntradaSemVazarNada() throws Exception {
		MockHttpServletResponse r = call("/api/catalog/providers/nao-e-uuid/services", customer);

		assertEquals(400, r.getStatus());
		assertFalse(r.getContentAsString().contains("Exception"));
	}

	// --- /api/appointments/mine com nomes ---

	@Test
	void meusAgendamentosTrazemNegocioFusoENomeDoServicoMesmoDepoisDeDesativar() throws Exception {
		UUID serviceId = jdbc.queryForObject("SELECT id FROM services WHERE provider_id = ? AND name = 'Troca de óleo'", UUID.class, providerA);
		UUID customerId = jdbc.queryForObject("SELECT id FROM users WHERE email = 'cliente@exemplo.test'", UUID.class);
		jdbc.update("INSERT INTO appointments (provider_id, service_id, customer_id, starts_at, ends_at, status) VALUES (?, ?, ?, now() + interval '2 days', now() + interval '2 days 1 hour', 'SCHEDULED')",
				providerA, serviceId, customerId);
		jdbc.update("UPDATE services SET active = false WHERE id = ?", serviceId);
		jdbc.update("UPDATE users SET active = false WHERE id = (SELECT user_id FROM providers WHERE id = ?)", providerA);

		mvc.perform(get("/api/appointments/mine").header(HttpHeaders.AUTHORIZATION, customer)).andExpect(status().isOk())
				.andExpect(jsonPath("$[0].businessName").value("Auto Center Alfa"))
				.andExpect(jsonPath("$[0].timeZone").value("America/Manaus"))
				.andExpect(jsonPath("$[0].serviceName").value("Troca de óleo"))
				.andExpect(jsonPath("$[0].status").value("SCHEDULED"));
	}

	@Test
	void meusAgendamentosNuncaMostramOsDeOutroCliente() throws Exception {
		UUID serviceId = jdbc.queryForObject("SELECT id FROM services WHERE provider_id = ? LIMIT 1", UUID.class, providerA);
		UUID other = seedUser("outro@exemplo.test", "CUSTOMER", true);
		jdbc.update("INSERT INTO appointments (provider_id, service_id, customer_id, starts_at, ends_at, status) VALUES (?, ?, ?, now() + interval '2 days', now() + interval '2 days 1 hour', 'SCHEDULED')",
				providerA, serviceId, other);

		assertEquals("[]", call("/api/appointments/mine", customer).getContentAsString());
	}
}
