package com.queuecare.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.TestPropertySource;

/**
 * O contrato OpenAPI é GERADO do código e o arquivo {@code docs/openapi.json} é conferido contra ele: quem muda um endpoint, um DTO
 * ou uma regra de perfil sem atualizar o contrato quebra este teste. Para atualizar o arquivo, rode
 * {@code ./mvnw test -Dtest=OpenApiContractTest -Dopenapi.update=true} e revise o diff.
 */
// As rotas de sonda dos testes (/api/test/**) nunca entram no contrato publicado.
@TestPropertySource(properties = { "springdoc.api-docs.enabled=true", "springdoc.paths-to-exclude=/api/test/**" })
class OpenApiContractTest extends AuthIntegrationTestBase {

	private static final ObjectMapper MAPPER = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT).enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
	private static final Path FILE = Path.of("..", "docs", "openapi.json");

	private String cached;

	/** Gera o contrato UMA vez por teste (o usuário administrador também é criado uma vez). */
	private String generated() throws Exception {
		if (cached != null) {
			return cached;
		}
		String token = "Bearer " + validToken(seedUser("admin@exemplo.test", "ADMIN", true));
		String body = mvc.perform(get("/v3/api-docs").header(HttpHeaders.AUTHORIZATION, token)).andExpect(status().isOk()).andReturn().getResponse()
				.getContentAsString(StandardCharsets.UTF_8);
		@SuppressWarnings("unchecked")
		Map<String, Object> tree = MAPPER.readValue(body, Map.class);
		tree.remove("servers"); // o endereço muda por ambiente
		cached = (MAPPER.writeValueAsString(tree) + "\n").replace("\r\n", "\n"); // o mesmo arquivo em Windows e Linux
		return cached;
	}

	private JsonNode spec() throws Exception {
		return MAPPER.readTree(generated());
	}

	@Test
	void arquivoPublicadoEIgualAoContratoGeradoDoCodigo() throws Exception {
		String current = generated();
		if (Boolean.getBoolean("openapi.update")) {
			Files.writeString(FILE, current, StandardCharsets.UTF_8);
		}
		assertTrue(Files.exists(FILE), "docs/openapi.json não existe: gere com -Dopenapi.update=true");
		assertEquals(Files.readString(FILE, StandardCharsets.UTF_8).replace("\r\n", "\n"), current,
				"docs/openapi.json está desatualizado: rode com -Dopenapi.update=true e revise o diff");
	}

	@Test
	void todaOperacaoDizQuemPodeChamarEOsPerfisVemDaRegraReal() throws Exception {
		JsonNode paths = spec().get("paths");
		assertTrue(paths.size() > 25, "esperava todos os endpoints, veio " + paths.size());
		paths.forEach(path -> path.forEach(op -> {
			String description = op.path("description").asText();
			assertTrue(description.contains("Perfis:") || description.contains("Público") || description.contains("autenticado"), "operação sem quem-pode: " + op);
		}));
		assertTrue(operation("/api/admin/users", "get").get("description").asText().contains("Perfis: ADMIN"));
		String checkIn = operation("/api/appointments/{id}/check-in", "post").get("description").asText();
		assertTrue(checkIn.contains("PROVIDER") && checkIn.contains("ATTENDANT") && !checkIn.contains("CUSTOMER"), checkIn);
		assertTrue(operation("/api/appointments/mine", "get").get("description").asText().contains("Perfis: CUSTOMER"));
		assertTrue(operation("/api/agenda/queue", "get").get("description").asText().contains("PROVIDER, ATTENDANT"));
	}

	private JsonNode operation(String path, String method) throws Exception {
		JsonNode node = spec().path("paths").path(path).path(method);
		assertFalse(node.isMissingNode(), "falta " + method + " " + path);
		return node;
	}

	@Test
	void rotasPublicasNaoExigemTokenENenhumaOutraEPublica() throws Exception {
		JsonNode paths = spec().get("paths");
		for (String publicRoute : new String[] { "/api/auth/login", "/api/auth/register" }) {
			JsonNode security = paths.path(publicRoute).path("post").path("security");
			assertTrue(security.isArray() && security.isEmpty(), publicRoute + " deve ser pública (security vazio)");
		}
		paths.fieldNames().forEachRemaining(path -> {
			if (!path.startsWith("/api/auth/login") && !path.startsWith("/api/auth/register")) {
				paths.get(path).forEach(op -> assertFalse(op.has("security") && op.get("security").isEmpty(), path + " não deveria ser pública"));
			}
		});
		assertEquals("bearer", spec().at("/components/securitySchemes/bearerAuth/scheme").asText());
	}

	@Test
	void contratoNuncaExpoeSenhaHashNemVersaoDeCredenciais() throws Exception {
		String lower = generated().toLowerCase();
		assertFalse(lower.contains("passwordhash") || lower.contains("password_hash") || lower.contains("tokenversion"), "o contrato expõe campo sensível");
	}

	@Test
	void esquemaProblemDescreveTodosOsCamposComOsTiposReais() throws Exception {
		JsonNode props = spec().at("/components/schemas/Problem/properties");
		assertEquals("integer", props.at("/status/type").asText());
		for (String text : new String[] { "type", "title", "detail", "instance", "reason" }) {
			assertEquals("string", props.at("/" + text + "/type").asText(), text);
		}
		assertEquals("uri", props.at("/type/format").asText());
		assertEquals("object", props.at("/errors/type").asText());
		assertEquals("string", props.at("/errors/additionalProperties/type").asText());
		assertEquals(7, props.size(), "campos do Problem");
	}

	@Test
	void respostas401e403e429DescrevemOQueAApiEntrega() throws Exception {
		JsonNode responses = operation("/api/appointments/{id}/cancel", "post").get("responses");
		JsonNode unauthorized = responses.get("401");
		assertFalse(unauthorized.has("content"), "401 tem corpo vazio: não pode prometer esquema");
		assertTrue(unauthorized.get("description").asText().contains("WWW-Authenticate"));
		assertTrue(responses.at("/403/description").asText().contains("PASSWORD_CHANGE_REQUIRED"));
		assertEquals("#/components/schemas/Problem", responses.at("/403/content/application~1problem+json/schema/$ref").asText());
		assertFalse(responses.has("429"), "cancelar não passa por limite de requisições");
	}

	@Test
	void respostaDe429SoExisteNasRotasRealmenteLimitadas() throws Exception {
		JsonNode paths = spec().get("paths");
		java.util.Set<String> expected = java.util.Set.of("post /api/auth/login", "post /api/auth/register", "post /api/auth/change-password", "post /api/admin/providers",
				"post /api/provider/staff/accounts", "get /api/catalog/providers", "get /api/catalog/providers/{providerId}/services", "get /api/providers/{providerId}/slots");
		java.util.Set<String> found = new java.util.TreeSet<>();
		paths.fieldNames().forEachRemaining(path -> paths.get(path).fieldNames().forEachRemaining(method -> {
			JsonNode op = paths.get(path).get(method);
			if (op.path("responses").has("429")) {
				found.add(method + " " + path);
				assertEquals("#/components/schemas/Problem", op.at("/responses/429/content/application~1problem+json/schema/$ref").asText());
				assertEquals("integer", op.at("/responses/429/headers/Retry-After/schema/type").asText());
			}
		}));
		assertEquals(new java.util.TreeSet<>(expected), found, "429 só nas rotas limitadas");
		assertFalse(paths.toString().contains("/api/test/"), "rotas de sonda dos testes não entram no contrato");
	}
}
