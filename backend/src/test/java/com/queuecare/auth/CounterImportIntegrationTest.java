package com.queuecare.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Importação de clientes de uma planilha (ADR 0034): só o dono, até 200 linhas, cada linha independente, sem duplicar telefone (do estúdio nem do próprio arquivo),
 * sem adivinhar espécie de pet, sem devolver dado pessoal na resposta, e com limite de importações por hora.
 */
class CounterImportIntegrationTest extends AuthIntegrationTestBase {

	UUID ownerA, ownerB, attendantA, joao;
	UUID providerA, providerB, serviceA;
	String tokenOwnerA, tokenOwnerB, tokenAttendantA, tokenJoao;
	private final ObjectMapper mapper = new ObjectMapper();

	@BeforeEach
	void seed() throws Exception {
		ownerA = seedUser("ia@exemplo.test", "PROVIDER", true);
		ownerB = seedUser("ib@exemplo.test", "PROVIDER", true);
		attendantA = seedUser("ia1@exemplo.test", "ATTENDANT", true);
		joao = seedUser("joao-imp@exemplo.test", "CUSTOMER", true);
		providerA = business(ownerA, "Estúdio A");
		providerB = business(ownerB, "Estúdio B");
		jdbc.update("INSERT INTO provider_staff (provider_id, user_id) VALUES (?, ?)", providerA, attendantA);
		serviceA = jdbc.queryForObject("INSERT INTO services (provider_id, name, duration_minutes) VALUES (?, 'Banho', 60) RETURNING id", UUID.class, providerA);
		tokenOwnerA = tokenOf("ia@exemplo.test");
		tokenOwnerB = tokenOf("ib@exemplo.test");
		tokenAttendantA = tokenOf("ia1@exemplo.test");
		tokenJoao = tokenOf("joao-imp@exemplo.test");
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

	private static Map<String, Object> row(String name, String phone, String email, String pet, String species) {
		Map<String, Object> row = new LinkedHashMap<>();
		row.put("name", name);
		row.put("phone", phone);
		row.put("email", email);
		row.put("petName", pet);
		row.put("petSpecies", species);
		return row;
	}

	private ResultActions importRows(String bearer, List<Map<String, Object>> rows) throws Exception {
		return mvc.perform(post("/api/agenda/customers/import").header(HttpHeaders.AUTHORIZATION, bearer).contentType(MediaType.APPLICATION_JSON)
				.content(mapper.writeValueAsString(Map.of("rows", rows))));
	}

	@Test
	void importaLinhasValidasLigaAsAoEstudioESemAcessoEPetSoComEspecieReconhecida() throws Exception {
		importRows(tokenOwnerA, List.of(
				row("Dona Marta", "(11) 91234-5678", null, "Bidu", "DOG"),
				row("Seu Zé", "21 3333-4444", "ze@exemplo.test", "Mimi", "CAT"),
				row("Ana Lima", "11 98888-7777", null, "Rex", null),
				row("Carla", "(11) 97777-6666", null, null, null)))
				.andExpect(status().isOk()).andExpect(jsonPath("$.created").value(4)).andExpect(jsonPath("$.petsCreated").value(2)).andExpect(jsonPath("$.petsSkipped").value(1))
				.andExpect(jsonPath("$.rows[0].status").value("CREATED")).andExpect(jsonPath("$.rows[0].petCreated").value(true))
				.andExpect(jsonPath("$.rows[2].petCreated").value(false)).andExpect(jsonPath("$.rows[2].petSkipped").value(true))
				.andExpect(jsonPath("$.rows[3].petSkipped").value(false));

		assertEquals(4, count("SELECT count(*) FROM users WHERE created_by_provider_id = ?", providerA));
		assertEquals(4, count("SELECT count(*) FROM users WHERE created_by_provider_id = ? AND must_change_password = true AND role = 'CUSTOMER'", providerA));
		assertEquals(3, count("SELECT count(*) FROM users WHERE created_by_provider_id = ? AND email LIKE 'balcao-%@balcao.invalid'", providerA), "sem e-mail: endereço reservado");
		assertEquals("ze@exemplo.test", jdbc.queryForObject("SELECT email FROM users WHERE name = 'Seu Zé'", String.class));
		assertEquals("5511912345678", jdbc.queryForObject("SELECT phone FROM users WHERE name = 'Dona Marta'", String.class));
		assertEquals(2, count("SELECT count(*) FROM pets p JOIN users u ON u.id = p.customer_id WHERE u.created_by_provider_id = ?", providerA));
		assertEquals(0, count("SELECT count(*) FROM pets WHERE name = 'Rex'"), "espécie não reconhecida: o pet fica de fora, sem adivinhar");
		assertEquals(4, count("SELECT count(*) FROM audit_log WHERE action = 'CUSTOMER_CREATED_BY_STUDIO' AND actor_user_id = ?", ownerA));
	}

	@Test
	void naoDuplicaTelefoneDoEstudioNemDoProprioArquivoMasOutroEstudioNaoImpede() throws Exception {
		// o estúdio B já tem a Marta; o estúdio A não: importar no A cria
		importRows(tokenOwnerB, List.of(row("Marta do B", "(11) 91234-5678", null, null, null))).andExpect(jsonPath("$.created").value(1));
		importRows(tokenOwnerA, List.of(
				row("Dona Marta", "(11) 91234-5678", null, null, null),
				row("Marta de novo", "11 91234-5678", null, null, null), // mesmo telefone no próprio arquivo, com outra máscara
				row("Joana", "(11) 95555-4444", null, null, null)))
				.andExpect(jsonPath("$.created").value(2)).andExpect(jsonPath("$.duplicates").value(1)).andExpect(jsonPath("$.rows[1].status").value("DUPLICATE_PHONE"));
		// repetir a importação inteira não cria ninguém: o estúdio já tem os dois telefones
		importRows(tokenOwnerA, List.of(row("Dona Marta", "(11) 91234-5678", null, null, null), row("Joana", "(11) 95555-4444", null, null, null)))
				.andExpect(jsonPath("$.created").value(0)).andExpect(jsonPath("$.duplicates").value(2));
		assertEquals(2, count("SELECT count(*) FROM users WHERE created_by_provider_id = ?", providerA));
	}

	@Test
	void naoDuplicaTutorQueOEstudioJaAtendeu() throws Exception {
		jdbc.update("UPDATE users SET phone = '5511933332222' WHERE id = ?", joao);
		UUID pet = jdbc.queryForObject("INSERT INTO pets (customer_id, name, species) VALUES (?, 'Thor', 'DOG') RETURNING id", UUID.class, joao);
		jdbc.update("INSERT INTO appointments (provider_id, service_id, customer_id, starts_at, ends_at, status, pet_id, pet_name, pet_species) "
				+ "VALUES (?, ?, ?, now() - interval '5 days', now() - interval '5 days' + interval '1 hour', 'COMPLETED', ?, 'Thor', 'DOG')", providerA, serviceA, joao, pet);
		importRows(tokenOwnerA, List.of(row("João (planilha)", "(11) 93333-2222", null, null, null))).andExpect(jsonPath("$.duplicates").value(1)).andExpect(jsonPath("$.created").value(0));
	}

	@Test
	void linhaRuimNaoDerrubaAsOutrasERelataSoONumeroEOMotivo() throws Exception {
		String body = importRows(tokenOwnerA, List.of(
				row("", "(11) 91234-5678", null, null, null), // sem nome
				row("Sem Telefone", "", null, null, null),
				row("Telefone Ruim", "123", null, null, null),
				row("Email Ruim", "(11) 92222-3333", "isso-nao-e-email", null, null),
				row("João Já Tem App", "(11) 94444-5555", "joao-imp@exemplo.test", null, null),
				row("Ok", "(11) 96666-7777", null, null, null)))
				.andExpect(status().isOk()).andExpect(jsonPath("$.created").value(1)).andExpect(jsonPath("$.invalid").value(4)).andExpect(jsonPath("$.emailTaken").value(1))
				.andExpect(jsonPath("$.rows[4].status").value("EMAIL_TAKEN")).andExpect(jsonPath("$.rows[5].status").value("CREATED"))
				.andReturn().getResponse().getContentAsString();
		for (String personal : List.of("Telefone Ruim", "91234", "joao-imp", "isso-nao-e-email", "Email Ruim", "Ok")) {
			assertFalse(body.contains(personal), "a resposta não repete dado da planilha: " + personal);
		}
		assertEquals(1, count("SELECT count(*) FROM users WHERE created_by_provider_id IS NOT NULL"));
	}

	@Test
	void soODonoImportaOAtendenteOTutorENinguemSemLoginNao() throws Exception {
		List<Map<String, Object>> rows = List.of(row("Dona Marta", "(11) 91234-5678", null, null, null));
		importRows(tokenAttendantA, rows).andExpect(status().isForbidden());
		importRows(tokenJoao, rows).andExpect(status().isForbidden());
		mvc.perform(post("/api/agenda/customers/import").contentType(MediaType.APPLICATION_JSON).content("{\"rows\":[]}")).andExpect(status().isUnauthorized());
		assertEquals(0, count("SELECT count(*) FROM users WHERE created_by_provider_id IS NOT NULL"));
	}

	@Test
	void recusaMaisDe200LinhasECorpoSemLinhas() throws Exception {
		List<Map<String, Object>> many = new ArrayList<>();
		for (int i = 0; i < 201; i++) {
			many.add(row("Pessoa " + i, "(11) 9" + String.format("%04d", i) + "-0000", null, null, null));
		}
		importRows(tokenOwnerA, many).andExpect(status().isBadRequest());
		mvc.perform(post("/api/agenda/customers/import").header(HttpHeaders.AUTHORIZATION, tokenOwnerA).contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isBadRequest());
		assertEquals(0, count("SELECT count(*) FROM users WHERE created_by_provider_id IS NOT NULL"));
	}

	@Test
	void aceitaAs200LinhasDeUmaVez() throws Exception {
		List<Map<String, Object>> rows = new ArrayList<>();
		for (int i = 0; i < 200; i++) {
			rows.add(row("Pessoa " + i, "(11) 9" + String.format("%04d", i) + "-0000", null, null, null));
		}
		importRows(tokenOwnerA, rows).andExpect(status().isOk()).andExpect(jsonPath("$.created").value(200));
		assertEquals(200, count("SELECT count(*) FROM users WHERE created_by_provider_id = ?", providerA));
	}

	@Test
	void limiteDeImportacoesPorHoraVaiParaOEstudioInteiro() throws Exception {
		for (int i = 0; i < 5; i++) {
			importRows(tokenOwnerA, List.of(row("Pessoa " + i, "(11) 9" + String.format("%04d", i) + "-1111", null, null, null))).andExpect(status().isOk());
		}
		importRows(tokenOwnerA, List.of(row("Outra", "(11) 98888-2222", null, null, null))).andExpect(status().isTooManyRequests());
		importRows(tokenOwnerB, List.of(row("Do B", "(11) 98888-3333", null, null, null))).andExpect(status().isOk()); // o limite é por estúdio
		assertEquals(5, count("SELECT count(*) FROM users WHERE created_by_provider_id = ?", providerA));
	}
}
