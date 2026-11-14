package com.queuecare.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.ResultActions;

/** Listagem de contas (ADR 0007): só o ADMIN, sem senha nem hash, com filtro, busca e paginação estáveis, e com HTTP e PostgreSQL reais. */
class AdminUserListIntegrationTest extends AuthIntegrationTestBase {

	String admin;
	UUID adminId;

	@BeforeEach
	void seed() {
		adminId = seedUser("admin@exemplo.test", "ADMIN", true);
		admin = "Bearer " + validToken(adminId);
	}

	private ResultActions list(String bearer, String... params) throws Exception {
		var builder = get("/api/admin/users").header(HttpHeaders.AUTHORIZATION, bearer);
		for (int i = 0; i < params.length; i += 2) {
			builder = builder.param(params[i], params[i + 1]);
		}
		return mvc.perform(builder);
	}

	private List<String> emails(String... params) throws Exception {
		String body = list(admin, params).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		return JsonPath.read(body, "$[*].email");
	}

	@Test
	void adminVeContasComPerfilSituacaoENuncaSenhaOuHash() throws Exception {
		UUID provider = seedUser("oficina@exemplo.test", "PROVIDER", true, true);
		seedUser("inativo@exemplo.test", "CUSTOMER", false);

		String body = list(admin).andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(3))
				.andExpect(jsonPath("$[?(@.email=='oficina@exemplo.test')].role").value("PROVIDER"))
				.andExpect(jsonPath("$[?(@.email=='oficina@exemplo.test')].mustChangePassword").value(true))
				.andExpect(jsonPath("$[?(@.email=='oficina@exemplo.test')].id").value(provider.toString()))
				.andExpect(jsonPath("$[?(@.email=='inativo@exemplo.test')].active").value(false))
				.andExpect(jsonPath("$[?(@.email=='admin@exemplo.test')].role").value("ADMIN"))
				.andReturn().getResponse().getContentAsString();

		String lower = body.toLowerCase();
		assertFalse(lower.contains("hash") || body.contains("$2") || lower.contains("\"password\"") || lower.contains("initialpassword"), "a resposta não traz senha nem hash: " + body);
		assertFalse(lower.contains("tokenversion"), "nem a versão das credenciais");
	}

	@Test
	void somenteOAdminListaOsOutrosPerfisRecebem403EQuemNaoTemTokenRecebe401() throws Exception {
		for (String role : List.of("PROVIDER", "ATTENDANT", "CUSTOMER")) {
			String token = "Bearer " + validToken(seedUser(role.toLowerCase() + "@exemplo.test", role, true));
			list(token).andExpect(status().isForbidden());
		}
		mvc.perform(get("/api/admin/users")).andExpect(status().isUnauthorized());
		mvc.perform(get("/api/admin/users").header(HttpHeaders.AUTHORIZATION, "Bearer invalido")).andExpect(status().isUnauthorized());
	}

	@Test
	void ordemEstavelPorEmailEPaginacaoSemRepetirNemPerder() throws Exception {
		for (String e : List.of("carla@exemplo.test", "bruno@exemplo.test", "Ana@exemplo.test", "daniel@exemplo.test")) {
			seedUser(e.toLowerCase(), "CUSTOMER", true);
		}
		List<String> all = emails();
		assertEquals(List.of("admin@exemplo.test", "ana@exemplo.test", "bruno@exemplo.test", "carla@exemplo.test", "daniel@exemplo.test"), all);

		assertEquals(List.of("admin@exemplo.test", "ana@exemplo.test"), emails("size", "2", "page", "0"));
		assertEquals(List.of("bruno@exemplo.test", "carla@exemplo.test"), emails("size", "2", "page", "1"));
		assertEquals(List.of("daniel@exemplo.test"), emails("size", "2", "page", "2"));
		assertEquals(List.of(), emails("size", "2", "page", "3"));
	}

	@Test
	void filtraPorPerfilEBuscaNoNomeOuNoEmailSemDiferenciarMaiusculas() throws Exception {
		seedUser("maria@exemplo.test", "CUSTOMER", true);
		seedUser("oficina@exemplo.test", "PROVIDER", true);
		jdbc.update("UPDATE users SET name = 'Maria Souza' WHERE email = 'maria@exemplo.test'");

		assertEquals(List.of("oficina@exemplo.test"), emails("role", "PROVIDER"));
		assertEquals(List.of("admin@exemplo.test"), emails("role", "ADMIN"));
		assertEquals(List.of("maria@exemplo.test"), emails("q", "SOUZA"), "busca no nome, sem diferenciar maiúsculas");
		assertEquals(List.of("oficina@exemplo.test"), emails("q", "  OFIC "), "busca no e-mail, com espaços externos ignorados");
		assertEquals(List.of(), emails("role", "CUSTOMER", "q", "oficina"), "filtros combinados");
		assertEquals(3, emails("role", "").size(), "perfil em branco não filtra");
	}

	@Test
	void curingasDigitadosValemComoTextoComumNaoComoPadrao() throws Exception {
		seedUser("ana_silva@exemplo.test", "CUSTOMER", true);
		seedUser("anaxsilva@exemplo.test", "CUSTOMER", true);
		seedUser("cem%@exemplo.test", "CUSTOMER", true);

		assertEquals(List.of("ana_silva@exemplo.test"), emails("q", "ana_silva"), "o _ não é 'qualquer caractere'");
		assertEquals(List.of("cem%@exemplo.test"), emails("q", "%"), "o % não é 'qualquer coisa'");
		assertEquals(List.of(), emails("q", "!"), "o caractere de escape também é texto comum");
	}

	@Test
	void parametrosInvalidosRecebem400() throws Exception {
		list(admin, "size", "0").andExpect(status().isBadRequest());
		list(admin, "size", "51").andExpect(status().isBadRequest());
		list(admin, "page", "-1").andExpect(status().isBadRequest());
		list(admin, "role", "SUPERUSER").andExpect(status().isBadRequest());
		list(admin, "q", "x".repeat(101)).andExpect(status().isBadRequest());
		list(admin, "size", "50", "q", "x".repeat(100)).andExpect(status().isOk());
	}

	@Test
	void desativarRefleteNaListaEQuemDesativaOutroNaoMudaOsDadosDeLeitura() throws Exception {
		UUID target = seedUser("alvo@exemplo.test", "ATTENDANT", true);
		mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/admin/users/" + target + "/deactivate")
				.header(HttpHeaders.AUTHORIZATION, admin)).andExpect(status().isNoContent());

		list(admin, "q", "alvo").andExpect(status().isOk()).andExpect(jsonPath("$[0].active").value(false));
		assertEquals(1L, count("SELECT count(*) FROM audit_log WHERE action = 'USER_DEACTIVATED'"), "a leitura não grava auditoria");
	}
}
