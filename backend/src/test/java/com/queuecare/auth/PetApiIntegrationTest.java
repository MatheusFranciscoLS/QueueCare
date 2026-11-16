package com.queuecare.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Pets do tutor (ADR 0016) pela API real: tokens, filtros de segurança e PostgreSQL de verdade. Cobre o CRUD, a normalização,
 * o acesso por perfil, o isolamento entre clientes (pet alheio = pet inexistente) e os limites de 20 ativos e 100 no total.
 * A corrida dos limites está em {@link PetConcurrencyIntegrationTest}.
 */
class PetApiIntegrationTest extends AuthIntegrationTestBase {

	static final String BASE = "/api/customer/pets";

	UUID ownerA, ownerB;
	String tokenA, tokenB;

	@BeforeEach
	void seed() throws Exception {
		ownerA = seedUser("tutor-a@exemplo.test", "CUSTOMER", true);
		ownerB = seedUser("tutor-b@exemplo.test", "CUSTOMER", true);
		tokenA = tokenOf("tutor-a@exemplo.test");
		tokenB = tokenOf("tutor-b@exemplo.test");
	}

	String tokenOf(String email) throws Exception {
		String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(json("email", email, "password", PASSWORD)))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		return "Bearer " + JsonPath.read(body, "$.accessToken");
	}

	/** Texto JSON entre aspas, com escape de quebras e controles (ou {@code null} literal). */
	static String q(String s) {
		if (s == null) {
			return "null";
		}
		StringBuilder sb = new StringBuilder("\"");
		for (char ch : s.toCharArray()) {
			switch (ch) {
				case '"' -> sb.append("\\\"");
				case '\\' -> sb.append("\\\\");
				case '\n' -> sb.append("\\n");
				case '\r' -> sb.append("\\r");
				case '\t' -> sb.append("\\t");
				default -> {
					if (ch < 0x20) {
						sb.append(String.format("\\u%04x", (int) ch));
					} else {
						sb.append(ch);
					}
				}
			}
		}
		return sb.append('"').toString();
	}

	static String body(String name, String species, String breed, String size, String notes) {
		return "{\"name\":" + q(name) + ",\"species\":" + q(species) + ",\"breed\":" + q(breed) + ",\"size\":" + q(size) + ",\"notes\":" + q(notes) + "}";
	}

	static String simple(String name) {
		return body(name, "DOG", null, null, null);
	}

	ResultActions send(MockHttpServletRequestBuilder request, String bearer, String content) throws Exception {
		MockHttpServletRequestBuilder r = request;
		if (bearer != null) {
			r = r.header(HttpHeaders.AUTHORIZATION, bearer);
		}
		if (content != null) {
			r = r.contentType(MediaType.APPLICATION_JSON).content(content);
		}
		return mvc.perform(r);
	}

	String create(String bearer, String content) throws Exception {
		MvcResult result = send(post(BASE), bearer, content).andExpect(status().isCreated()).andReturn();
		return JsonPath.read(result.getResponse().getContentAsString(), "$.id");
	}

	// --- CRUD ---

	@Test
	void criaListaObtemAtualizaArquivaEReativa() throws Exception {
		send(post(BASE), tokenA, body("Mel", "CAT", "Siamês", "SMALL", "Tem medo de secador.")).andExpect(status().isCreated())
				.andExpect(jsonPath("$.name").value("Mel")).andExpect(jsonPath("$.species").value("CAT")).andExpect(jsonPath("$.breed").value("Siamês"))
				.andExpect(jsonPath("$.size").value("SMALL")).andExpect(jsonPath("$.notes").value("Tem medo de secador.")).andExpect(jsonPath("$.active").value(true));
		String rex = create(tokenA, simple("Rex"));

		// lista por nome, sem distinguir maiúsculas; os dois ativos
		send(get(BASE), tokenA, null).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2)).andExpect(jsonPath("$[0].name").value("Mel"))
				.andExpect(jsonPath("$[1].name").value("Rex")).andExpect(jsonPath("$[1].breed").doesNotExist()).andExpect(jsonPath("$[1].size").doesNotExist());

		send(get(BASE + "/" + rex), tokenA, null).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(rex)).andExpect(jsonPath("$.name").value("Rex"));

		// substituição completa (campos omitidos viram vazios)
		send(put(BASE + "/" + rex), tokenA, body("Rex Jr", "DOG", "Poodle", "MEDIUM", null)).andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Rex Jr"))
				.andExpect(jsonPath("$.breed").value("Poodle")).andExpect(jsonPath("$.size").value("MEDIUM")).andExpect(jsonPath("$.notes").doesNotExist());
		send(put(BASE + "/" + rex), tokenA, simple("Rex Jr")).andExpect(status().isOk()).andExpect(jsonPath("$.breed").doesNotExist())
				.andExpect(jsonPath("$.size").doesNotExist());

		send(post(BASE + "/" + rex + "/archive"), tokenA, null).andExpect(status().isOk()).andExpect(jsonPath("$.active").value(false));
		send(get(BASE), tokenA, null).andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].name").value("Mel"));
		send(get(BASE).param("includeArchived", "true"), tokenA, null).andExpect(jsonPath("$.length()").value(2));
		send(get(BASE + "/" + rex), tokenA, null).andExpect(status().isOk()).andExpect(jsonPath("$.active").value(false));

		send(post(BASE + "/" + rex + "/reactivate"), tokenA, null).andExpect(status().isOk()).andExpect(jsonPath("$.active").value(true));
		send(get(BASE), tokenA, null).andExpect(jsonPath("$.length()").value(2));
		assertEquals(2L, count("SELECT count(*) FROM pets WHERE customer_id = ?", ownerA));
	}

	@Test
	void listaOrdenaPorNomeSemDistinguirMaiusculas() throws Exception {
		for (String n : List.of("bia", "Zeca", "Ana", "carlos")) {
			create(tokenA, simple(n));
		}
		String names = send(get(BASE), tokenA, null).andReturn().getResponse().getContentAsString();
		assertEquals(List.of("Ana", "bia", "carlos", "Zeca"), JsonPath.read(names, "$[*].name"));
	}

	@Test
	void textoEhNormalizadoAntesDeGravar() throws Exception {
		send(post(BASE), tokenA, body("  Rex    Jr  ", "DOG", "   ", "", "linha 1\r\nlinha 2\r\n")).andExpect(status().isCreated())
				.andExpect(jsonPath("$.name").value("Rex Jr")).andExpect(jsonPath("$.breed").doesNotExist()).andExpect(jsonPath("$.size").doesNotExist())
				.andExpect(jsonPath("$.notes").value("linha 1\nlinha 2"));
		assertEquals("Rex Jr", jdbc.queryForObject("SELECT name FROM pets WHERE customer_id = ?", String.class, ownerA));
		assertNull(jdbc.queryForObject("SELECT breed FROM pets WHERE customer_id = ?", String.class, ownerA));
		assertEquals("linha 1\nlinha 2", jdbc.queryForObject("SELECT notes FROM pets WHERE customer_id = ?", String.class, ownerA));
	}

	@Test
	void donoEEstadoNaoVemDoCorpo() throws Exception {
		String other = "{\"name\":\"Rex\",\"species\":\"DOG\",\"customerId\":\"" + ownerB + "\",\"active\":false,\"id\":\"" + UUID.randomUUID() + "\"}";
		String id = create(tokenA, other);
		assertEquals(ownerA, jdbc.queryForObject("SELECT customer_id FROM pets WHERE id = ?::uuid", UUID.class, id));
		assertTrue(jdbc.queryForObject("SELECT active FROM pets WHERE id = ?::uuid", Boolean.class, id));
		assertEquals(0L, count("SELECT count(*) FROM pets WHERE customer_id = ?", ownerB));
	}

	// --- validação ---

	@Test
	void dadosInvalidosRecebem400ComOMotivoPorCampoSemRepetirOValor() throws Exception {
		String secret = "observação-secreta-do-tutor";
		MvcResult result = send(post(BASE), tokenA, body("", "BIRD", "x".repeat(81), "HUGE", secret + "\u0007")).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.reason").value("INVALID_PET")).andExpect(jsonPath("$.errors.name").exists()).andExpect(jsonPath("$.errors.species").exists())
				.andExpect(jsonPath("$.errors.breed").exists()).andExpect(jsonPath("$.errors.size").exists()).andExpect(jsonPath("$.errors.notes").exists())
				.andReturn();
		String response = result.getResponse().getContentAsString();
		assertFalse(response.contains(secret), response);
		assertFalse(response.contains("BIRD"), response);
		assertEquals(0L, count("SELECT count(*) FROM pets"));
		// o mesmo vale para a edição (e o pet existente não muda)
		String id = create(tokenA, simple("Rex"));
		send(put(BASE + "/" + id), tokenA, body("Rex", "DOG", null, null, "x".repeat(501))).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.notes").value("até 500 caracteres"));
		assertEquals("Rex", jdbc.queryForObject("SELECT name FROM pets WHERE id = ?::uuid", String.class, id));
	}

	@Test
	void corpoAusenteOuMalFormadoRecebe400() throws Exception {
		send(post(BASE), tokenA, null).andExpect(status().isBadRequest());
		send(post(BASE), tokenA, "{nao é json").andExpect(status().isBadRequest());
		send(post(BASE), tokenA, "{}").andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.name").exists()).andExpect(jsonPath("$.errors.species").exists());
	}

	// --- acesso por perfil ---

	@Test
	void soClienteAcessaEsteCadastro() throws Exception {
		String own = create(tokenA, simple("Rex"));
		seedUser("prestador@exemplo.test", "PROVIDER", true);
		seedUser("atendente@exemplo.test", "ATTENDANT", true);
		seedUser("admin@exemplo.test", "ADMIN", true);
		for (String email : List.of("prestador@exemplo.test", "atendente@exemplo.test", "admin@exemplo.test")) {
			String token = tokenOf(email);
			send(get(BASE), token, null).andExpect(status().isForbidden());
			send(post(BASE), token, simple("Intruso")).andExpect(status().isForbidden());
			send(get(BASE + "/" + own), token, null).andExpect(status().isForbidden());
			send(put(BASE + "/" + own), token, simple("Intruso")).andExpect(status().isForbidden());
			send(post(BASE + "/" + own + "/archive"), token, null).andExpect(status().isForbidden());
			send(post(BASE + "/" + own + "/reactivate"), token, null).andExpect(status().isForbidden());
		}
		assertEquals(1L, count("SELECT count(*) FROM pets"));
		assertEquals("Rex", jdbc.queryForObject("SELECT name FROM pets", String.class));
		assertTrue(jdbc.queryForObject("SELECT active FROM pets", Boolean.class));
	}

	@Test
	void semTokenOuComTokenInvalidoRecebe401() throws Exception {
		send(get(BASE), null, null).andExpect(status().isUnauthorized());
		send(post(BASE), null, simple("Rex")).andExpect(status().isUnauthorized());
		send(get(BASE), "Bearer lixo", null).andExpect(status().isUnauthorized());
	}

	// --- isolamento entre clientes ---

	@Test
	void petDeOutroClienteEIndistinguivelDePetInexistente() throws Exception {
		String petA = create(tokenA, body("Rex", "DOG", "Poodle", "SMALL", "nota privada"));
		String ghost = UUID.randomUUID().toString();

		for (String target : List.of(petA, ghost)) {
			ResultActions get = send(get(BASE + "/" + target), tokenB, null);
			ResultActions put = send(put(BASE + "/" + target), tokenB, simple("Invadido"));
			ResultActions archive = send(post(BASE + "/" + target + "/archive"), tokenB, null);
			ResultActions reactivate = send(post(BASE + "/" + target + "/reactivate"), tokenB, null);
			for (ResultActions r : List.of(get, put, archive, reactivate)) {
				r.andExpect(status().isNotFound()).andExpect(jsonPath("$.reason").value("PET_NOT_FOUND")).andExpect(jsonPath("$.detail").value("pet não encontrado"));
				String response = r.andReturn().getResponse().getContentAsString();
				assertFalse(response.contains("nota privada"), response);
				assertFalse(response.contains("Poodle"), response);
			}
		}
		// o pet de A segue intacto e B não vê nada
		assertEquals("Rex", jdbc.queryForObject("SELECT name FROM pets WHERE id = ?::uuid", String.class, petA));
		assertTrue(jdbc.queryForObject("SELECT active FROM pets WHERE id = ?::uuid", Boolean.class, petA));
		send(get(BASE).param("includeArchived", "true"), tokenB, null).andExpect(jsonPath("$.length()").value(0));
	}

	@Test
	void idQueNaoEUuidRecebe400() throws Exception {
		send(get(BASE + "/nao-e-uuid"), tokenA, null).andExpect(status().isBadRequest());
	}

	// --- arquivar e reativar ---

	@Test
	void arquivarEReativarSaoIdempotentes() throws Exception {
		String id = create(tokenA, simple("Rex"));
		send(post(BASE + "/" + id + "/reactivate"), tokenA, null).andExpect(status().isOk()).andExpect(jsonPath("$.active").value(true));
		send(post(BASE + "/" + id + "/archive"), tokenA, null).andExpect(status().isOk()).andExpect(jsonPath("$.active").value(false));
		send(post(BASE + "/" + id + "/archive"), tokenA, null).andExpect(status().isOk()).andExpect(jsonPath("$.active").value(false));
		send(post(BASE + "/" + id + "/reactivate"), tokenA, null).andExpect(status().isOk()).andExpect(jsonPath("$.active").value(true));
	}

	@Test
	void petArquivadoPodeSerCorrigidoSemReativar() throws Exception {
		String id = create(tokenA, simple("Rex"));
		send(post(BASE + "/" + id + "/archive"), tokenA, null).andExpect(status().isOk());
		send(put(BASE + "/" + id), tokenA, simple("Rex Corrigido")).andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Rex Corrigido"))
				.andExpect(jsonPath("$.active").value(false));
	}

	// --- limites ---

	private void seedPets(UUID owner, int active, int archived) {
		for (int i = 0; i < active + archived; i++) {
			jdbc.update("INSERT INTO pets (customer_id, name, species, active) VALUES (?, ?, 'DOG', ?)", owner, "Pet " + i, i < active);
		}
	}

	@Test
	void limiteDeVintePetsAtivosEDeCadaCliente() throws Exception {
		for (int i = 0; i < 20; i++) {
			create(tokenA, simple("Pet " + i));
		}
		send(post(BASE), tokenA, simple("Pet 21")).andExpect(status().isConflict()).andExpect(jsonPath("$.reason").value("PET_LIMIT_REACHED"));
		assertEquals(20L, count("SELECT count(*) FROM pets WHERE customer_id = ?", ownerA));
		// o limite é por cliente
		create(tokenB, simple("Pet do B"));
		// arquivar abre vaga
		String first = jdbc.queryForObject("SELECT id::text FROM pets WHERE customer_id = ? ORDER BY name LIMIT 1", String.class, ownerA);
		send(post(BASE + "/" + first + "/archive"), tokenA, null).andExpect(status().isOk());
		create(tokenA, simple("Pet 21"));
		send(post(BASE), tokenA, simple("Pet 22")).andExpect(status().isConflict()).andExpect(jsonPath("$.reason").value("PET_LIMIT_REACHED"));
	}

	@Test
	void limiteDeCemPetsNoTotalVaiSoParaCriar() throws Exception {
		seedPets(ownerA, 10, 90); // 100 no total, 10 ativos
		send(post(BASE), tokenA, simple("Pet novo")).andExpect(status().isConflict()).andExpect(jsonPath("$.reason").value("PET_LIMIT_REACHED"))
				.andExpect(jsonPath("$.detail").value("limite de 100 pets cadastrados"));
		assertEquals(100L, count("SELECT count(*) FROM pets WHERE customer_id = ?", ownerA));
		// reativar NÃO cria registro: com 100 cadastrados e 10 ativos, reativar um arquivado é permitido
		String archived = jdbc.queryForObject("SELECT id::text FROM pets WHERE customer_id = ? AND NOT active LIMIT 1", String.class, ownerA);
		send(post(BASE + "/" + archived + "/reactivate"), tokenA, null).andExpect(status().isOk()).andExpect(jsonPath("$.active").value(true));
		assertEquals(11L, count("SELECT count(*) FROM pets WHERE customer_id = ? AND active", ownerA));
	}

	@Test
	void reativarRespeitaOLimiteDeAtivos() throws Exception {
		seedPets(ownerA, 20, 5);
		String archived = jdbc.queryForObject("SELECT id::text FROM pets WHERE customer_id = ? AND NOT active LIMIT 1", String.class, ownerA);
		send(post(BASE + "/" + archived + "/reactivate"), tokenA, null).andExpect(status().isConflict()).andExpect(jsonPath("$.reason").value("PET_LIMIT_REACHED"));
		assertFalse(jdbc.queryForObject("SELECT active FROM pets WHERE id = ?::uuid", Boolean.class, archived));
		assertEquals(20L, count("SELECT count(*) FROM pets WHERE customer_id = ? AND active", ownerA));
		// pet que já está ativo continua respondendo 200 mesmo com 20 ativos
		String active = jdbc.queryForObject("SELECT id::text FROM pets WHERE customer_id = ? AND active LIMIT 1", String.class, ownerA);
		send(post(BASE + "/" + active + "/reactivate"), tokenA, null).andExpect(status().isOk());
	}
}
