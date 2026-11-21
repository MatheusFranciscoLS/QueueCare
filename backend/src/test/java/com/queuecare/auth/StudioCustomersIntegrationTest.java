package com.queuecare.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Os clientes do estúdio e a senha temporária (ADR 0025): a equipe acha o tutor no balcão por nome (sem acento), telefone ou pet, e só vê quem o estúdio já
 * atendeu; só o DONO gera a senha temporária, só de tutor atendido por ele, uma vez, com troca obrigatória, auditoria e limite por hora.
 */
class StudioCustomersIntegrationTest extends AuthIntegrationTestBase {

	UUID ownerA, ownerB, attendantA, attendantB, joao, maria, estranho, inativo, nuncaAtendido;
	UUID providerA, providerB, serviceA, serviceB;
	String tokenOwnerA, tokenOwnerB, tokenAttendantA, tokenAttendantB, tokenJoao, tokenAdmin;
	private int seq;

	@BeforeEach
	void seed() throws Exception {
		ownerA = seedUser("sa@exemplo.test", "PROVIDER", true);
		ownerB = seedUser("sb@exemplo.test", "PROVIDER", true);
		attendantA = seedUser("sa1@exemplo.test", "ATTENDANT", true);
		attendantB = seedUser("sb1@exemplo.test", "ATTENDANT", true);
		joao = seedUser("joao@exemplo.test", "CUSTOMER", true);
		maria = seedUser("maria@exemplo.test", "CUSTOMER", true);
		estranho = seedUser("estranho@exemplo.test", "CUSTOMER", true);
		inativo = seedUser("inativo@exemplo.test", "CUSTOMER", false);
		nuncaAtendido = seedUser("nunca@exemplo.test", "CUSTOMER", true);
		UUID admin = seedUser("sadmin@exemplo.test", "ADMIN", true);
		assertTrue(admin != null);
		jdbc.update("UPDATE users SET name = 'João da Silva', phone = '5511987654321' WHERE id = ?", joao);
		jdbc.update("UPDATE users SET name = 'Maria Souza', phone = '5521911112222' WHERE id = ?", maria);
		jdbc.update("UPDATE users SET name = 'Estranho de Outro Estúdio' WHERE id = ?", estranho);
		jdbc.update("UPDATE users SET name = 'Inativo Antigo' WHERE id = ?", inativo);
		providerA = business(ownerA, "Estúdio A");
		providerB = business(ownerB, "Estúdio B");
		jdbc.update("INSERT INTO provider_staff (provider_id, user_id) VALUES (?, ?)", providerA, attendantA);
		jdbc.update("INSERT INTO provider_staff (provider_id, user_id) VALUES (?, ?)", providerB, attendantB);
		serviceA = jdbc.queryForObject("INSERT INTO services (provider_id, name, duration_minutes) VALUES (?, 'Banho', 60) RETURNING id", UUID.class, providerA);
		serviceB = jdbc.queryForObject("INSERT INTO services (provider_id, name, duration_minutes) VALUES (?, 'Banho', 60) RETURNING id", UUID.class, providerB);

		// Estúdio A atendeu o João (2 concluídos e 1 falta; pets Thor e Mel) e a Maria (1 concluído; pet Pipoca) e o inativo; o estranho é só do B.
		appointment(providerA, serviceA, joao, "COMPLETED", "Thor", 20);
		appointment(providerA, serviceA, joao, "COMPLETED", "Mel", 10);
		appointment(providerA, serviceA, joao, "NO_SHOW", "Thor", 5);
		appointment(providerA, serviceA, maria, "COMPLETED", "Pipoca", 15);
		appointment(providerA, serviceA, inativo, "COMPLETED", "Fantasma", 30);
		appointment(providerB, serviceB, estranho, "COMPLETED", "Rex", 8);

		tokenOwnerA = tokenOf("sa@exemplo.test");
		tokenOwnerB = tokenOf("sb@exemplo.test");
		tokenAttendantA = tokenOf("sa1@exemplo.test");
		tokenAttendantB = tokenOf("sb1@exemplo.test");
		tokenJoao = tokenOf("joao@exemplo.test");
		tokenAdmin = tokenOf("sadmin@exemplo.test");
	}

	private UUID business(UUID owner, String name) {
		return jdbc.queryForObject("INSERT INTO providers (user_id, business_name, time_zone, minimum_notice_minutes, cancellation_deadline_minutes) "
				+ "VALUES (?, ?, 'America/Sao_Paulo', 0, 120) RETURNING id", UUID.class, owner, name);
	}

	private void appointment(UUID provider, UUID service, UUID customer, String status, String petName, int daysAgo) {
		UUID pet = jdbc.queryForObject("INSERT INTO pets (customer_id, name, species) VALUES (?, ?, 'DOG') RETURNING id", UUID.class, customer, petName);
		OffsetDateTime start = OffsetDateTime.now().minusDays(daysAgo).withHour(9).withMinute(0).withSecond(0).withNano(0).plusMinutes(++seq * 61L % 5);
		jdbc.update("INSERT INTO appointments (provider_id, service_id, customer_id, starts_at, ends_at, status, pet_id, pet_name, pet_species) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'DOG')",
				provider, service, customer, start, start.plusMinutes(60), status, pet, petName);
	}

	private String tokenOf(String email) throws Exception {
		String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(json("email", email, "password", PASSWORD)))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		return "Bearer " + JsonPath.read(body, "$.accessToken");
	}

	private ResultActions search(String bearer, String q) throws Exception {
		return mvc.perform(get("/api/agenda/customers").param("q", q).header(HttpHeaders.AUTHORIZATION, bearer));
	}

	private ResultActions reset(String bearer, UUID customer) throws Exception {
		return mvc.perform(post("/api/provider/customers/" + customer + "/reset-password").header(HttpHeaders.AUTHORIZATION, bearer));
	}

	// ---------- busca ----------

	@Test
	void semTextoListaOsClientesDoEstudioDoMaisRecenteParaOMaisAntigoComContagens() throws Exception {
		String body = search(tokenOwnerA, "").andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		List<String> names = JsonPath.read(body, "$[*].name");
		assertEquals(List.of("João da Silva", "Maria Souza"), names, "o inativo e o cliente de outro estúdio não aparecem, e o mais recente vem primeiro");
		assertEquals(2, (int) JsonPath.read(body, "$[0].visits"));
		assertEquals(1, (int) JsonPath.read(body, "$[0].noShows"));
		assertEquals(List.of("Mel", "Thor"), JsonPath.read(body, "$[0].pets"));
		assertEquals("5511987654321", JsonPath.read(body, "$[0].phone"));
		assertFalse(body.contains("@exemplo.test"), "e-mail nunca sai na busca");
		assertFalse(body.contains("password"), "nenhum dado de credencial");
	}

	@Test
	void achaPorNomeSemAcentoPorPetEPorTelefoneComTresDigitosOuMais() throws Exception {
		search(tokenOwnerA, "joao").andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].name").value("João da Silva"));
		search(tokenOwnerA, "SOUZA").andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].name").value("Maria Souza"));
		search(tokenOwnerA, "pipo").andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].name").value("Maria Souza"));
		search(tokenOwnerA, "(11) 98765").andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].name").value("João da Silva"));
		search(tokenOwnerA, "55").andExpect(jsonPath("$.length()").value(0)); // dois dígitos soltos não casam telefone
		search(tokenOwnerA, "---").andExpect(jsonPath("$.length()").value(0)); // só símbolos não casam tudo
		search(tokenOwnerA, "inexistente").andExpect(jsonPath("$.length()").value(0));
	}

	@Test
	void umEstudioNaoEnxergaOsClientesDoOutro() throws Exception {
		search(tokenOwnerB, "").andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].name").value("Estranho de Outro Estúdio"));
		search(tokenOwnerB, "joao").andExpect(jsonPath("$.length()").value(0));
		search(tokenAttendantB, "maria").andExpect(jsonPath("$.length()").value(0));
	}

	@Test
	void oAtendentePodeBuscarMasTutorEAdminNao() throws Exception {
		search(tokenAttendantA, "joao").andExpect(status().isOk()).andExpect(jsonPath("$[0].name").value("João da Silva"));
		search(tokenJoao, "joao").andExpect(status().isForbidden());
		search(tokenAdmin, "joao").andExpect(status().isForbidden());
		mvc.perform(get("/api/agenda/customers")).andExpect(status().isUnauthorized());
	}

	// ---------- senha temporária ----------

	@Test
	void odonoGeraUmaSenhaTemporariaQueFuncionaUmaVezEObrigaATroca() throws Exception {
		String body = reset(tokenOwnerA, joao).andExpect(status().isOk()).andExpect(header().string(HttpHeaders.CACHE_CONTROL, org.hamcrest.Matchers.containsString("no-store")))
				.andReturn().getResponse().getContentAsString();
		String temporary = JsonPath.read(body, "$.temporaryPassword");
		assertEquals(12, temporary.length());
		assertTrue(temporary.matches("[A-HJ-NP-Za-km-z2-9]{12}"), "só caracteres sem ambiguidade: " + temporary);

		// A senha antiga deixa de valer, a temporária entra e a troca é obrigatória; o token que o tutor já tinha também morre.
		mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(json("email", "joao@exemplo.test", "password", PASSWORD))).andExpect(status().isUnauthorized());
		mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(json("email", "joao@exemplo.test", "password", temporary))).andExpect(status().isOk())
				.andExpect(jsonPath("$.mustChangePassword").value(true));
		mvc.perform(get("/api/me").header(HttpHeaders.AUTHORIZATION, tokenJoao)).andExpect(status().isUnauthorized());

		assertEquals(1, count("SELECT count(*) FROM audit_log WHERE action = 'CUSTOMER_PASSWORD_RESET_BY_STUDIO' AND actor_user_id = ? AND target_user_id = ?", ownerA, joao));
		// (a tabela de auditoria nem tem coluna de texto livre: a senha não tem onde ser gravada)
	}

	@Test
	void duasSenhasTemporariasSaoDiferentes() throws Exception {
		String first = JsonPath.read(reset(tokenOwnerA, joao).andReturn().getResponse().getContentAsString(), "$.temporaryPassword");
		String second = JsonPath.read(reset(tokenOwnerA, joao).andReturn().getResponse().getContentAsString(), "$.temporaryPassword");
		assertFalse(first.equals(second));
	}

	@Test
	void qualquerAlvoInvalidoTemAMesmaRespostaDeNaoEncontrado() throws Exception {
		reset(tokenOwnerA, nuncaAtendido).andExpect(status().isNotFound()); // tutor que o estúdio nunca atendeu
		reset(tokenOwnerA, estranho).andExpect(status().isNotFound()); // tutor de outro estúdio
		reset(tokenOwnerA, inativo).andExpect(status().isNotFound()); // conta inativa
		reset(tokenOwnerA, ownerB).andExpect(status().isNotFound()); // não é tutor
		reset(tokenOwnerA, attendantA).andExpect(status().isNotFound());
		reset(tokenOwnerA, UUID.randomUUID()).andExpect(status().isNotFound()); // não existe
		assertEquals(0, count("SELECT count(*) FROM audit_log WHERE action = 'CUSTOMER_PASSWORD_RESET_BY_STUDIO'"));
		// ninguém teve a senha trocada
		mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(json("email", "nunca@exemplo.test", "password", PASSWORD))).andExpect(status().isOk());
	}

	@Test
	void soODonoGeraSenhaEUmEstudioNaoAlcancaOTutorDoOutro() throws Exception {
		reset(tokenAttendantA, joao).andExpect(status().isForbidden());
		reset(tokenJoao, joao).andExpect(status().isForbidden());
		reset(tokenAdmin, joao).andExpect(status().isForbidden());
		mvc.perform(post("/api/provider/customers/" + joao + "/reset-password")).andExpect(status().isUnauthorized());
		reset(tokenOwnerB, joao).andExpect(status().isNotFound()); // o B nunca atendeu o João
		mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(json("email", "joao@exemplo.test", "password", PASSWORD))).andExpect(status().isOk());
	}

	@Test
	void ha_um_limite_por_hora_por_estudio_e_o_outro_estudio_nao_e_afetado() throws Exception {
		for (int i = 0; i < 10; i++) {
			reset(tokenOwnerA, joao).andExpect(status().isOk());
		}
		reset(tokenOwnerA, maria).andExpect(status().isTooManyRequests()).andExpect(header().exists(HttpHeaders.RETRY_AFTER));
		reset(tokenOwnerB, estranho).andExpect(status().isOk());
	}
}
