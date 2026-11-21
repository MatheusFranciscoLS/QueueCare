package com.queuecare.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Sinal por Pix, manual (ADR 0023): o estúdio configura chave, valor e quando pedir (padrão: nunca); o servidor decide na reserva e congela o valor; o
 * tutor vê o que deve ANTES de confirmar (sem nunca saber a contagem de faltas); a equipe marca "recebido". Nada cancela sozinho.
 */
class DepositIntegrationTest extends AuthIntegrationTestBase {

	private static final ZoneId SP = ZoneId.of("America/Sao_Paulo");
	private static final String KEY = "estudio@exemplo.test";

	UUID ownerA, ownerB, tutor, otherTutor;
	UUID providerA, providerB, serviceA, petOfTutor;
	String tokenA, tokenB, tokenTutor, tokenOther;
	private int seq;

	@BeforeEach
	void seed() throws Exception {
		ownerA = seedUser("da@exemplo.test", "PROVIDER", true);
		ownerB = seedUser("db@exemplo.test", "PROVIDER", true);
		tutor = seedUser("dt@exemplo.test", "CUSTOMER", true);
		otherTutor = seedUser("do@exemplo.test", "CUSTOMER", true);
		providerA = jdbc.queryForObject("INSERT INTO providers (user_id, business_name, time_zone, minimum_notice_minutes, cancellation_deadline_minutes) "
				+ "VALUES (?, 'Negócio A', 'America/Sao_Paulo', 0, 120) RETURNING id", UUID.class, ownerA);
		providerB = jdbc.queryForObject("INSERT INTO providers (user_id, business_name, time_zone, minimum_notice_minutes, cancellation_deadline_minutes) "
				+ "VALUES (?, 'Negócio B', 'America/Sao_Paulo', 0, 120) RETURNING id", UUID.class, ownerB);
		jdbc.update("INSERT INTO availability_windows (provider_id, day_of_week, start_time, end_time) SELECT ?, d, '08:00', '18:00' FROM generate_series(1, 7) d", providerA);
		serviceA = jdbc.queryForObject("INSERT INTO services (provider_id, name, duration_minutes) VALUES (?, 'Banho', 60) RETURNING id", UUID.class, providerA);
		petOfTutor = jdbc.queryForObject("INSERT INTO pets (customer_id, name, species) VALUES (?, 'Rex', 'DOG') RETURNING id", UUID.class, tutor);
		tokenA = tokenOf("da@exemplo.test");
		tokenB = tokenOf("db@exemplo.test");
		tokenTutor = tokenOf("dt@exemplo.test");
		tokenOther = tokenOf("do@exemplo.test");
	}

	private String tokenOf(String email) throws Exception {
		String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(json("email", email, "password", PASSWORD)))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		return "Bearer " + JsonPath.read(body, "$.accessToken");
	}

	private ResultActions configure(String bearer, String body) throws Exception {
		return mvc.perform(put("/api/provider/deposit").header(HttpHeaders.AUTHORIZATION, bearer).contentType(MediaType.APPLICATION_JSON).content(body));
	}

	private void rule(String rule) throws Exception {
		configure(tokenA, "{\"pixKey\":\"" + KEY + "\",\"depositCents\":3000,\"depositRule\":\"" + rule + "\"}").andExpect(status().isOk());
	}

	/** Agendamento direto no banco (histórico do tutor), num dia distinto para não bater na restrição de sobreposição. */
	private void history(String statusName, int times) {
		for (int i = 0; i < times; i++) {
			OffsetDateTime start = OffsetDateTime.now().minusDays(10 + ++seq).withSecond(0).withNano(0);
			jdbc.update("INSERT INTO appointments (provider_id, service_id, customer_id, starts_at, ends_at, status, pet_id, pet_name, pet_species) VALUES (?, ?, ?, ?, ?, ?, ?, 'Rex', 'DOG')",
					providerA, serviceA, tutor, start, start.plusMinutes(60), statusName, petOfTutor);
		}
	}

	private String book(String bearer, UUID pet, int daysAhead) throws Exception {
		String start = LocalDate.now(SP).plusDays(daysAhead) + "T10:00:00";
		return mvc.perform(post("/api/appointments").header(HttpHeaders.AUTHORIZATION, bearer).contentType(MediaType.APPLICATION_JSON)
				.content("{\"providerId\":\"" + providerA + "\",\"serviceId\":\"" + serviceA + "\",\"petId\":\"" + pet + "\",\"localStart\":\"" + start + "\"}"))
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
	}

	private String terms(String bearer) throws Exception {
		return mvc.perform(get("/api/catalog/providers/" + providerA + "/deposit-terms").header(HttpHeaders.AUTHORIZATION, bearer)).andExpect(status().isOk()).andReturn()
				.getResponse().getContentAsString();
	}

	@Test
	void padraoEhNuncaPedirSinal() throws Exception {
		mvc.perform(get("/api/provider").header(HttpHeaders.AUTHORIZATION, tokenA)).andExpect(jsonPath("$.depositRule").value("NEVER")).andExpect(jsonPath("$.pixKey").doesNotExist())
				.andExpect(jsonPath("$.depositCents").doesNotExist());
		String booked = book(tokenTutor, petOfTutor, 3);
		assertEquals("NONE", JsonPath.read(booked, "$.depositStatus"));
		assertNull(JsonPath.read(booked, "$.depositCents"));
		assertNull(JsonPath.read(booked, "$.studioPixKey"));
		assertEquals(false, JsonPath.read(terms(tokenTutor), "$.required"));
	}

	@Test
	void estudioConfiguraEOServidorValidaCadaCampoSemRepetirAChave() throws Exception {
		String ok = configure(tokenA, "{\"pixKey\":\"  " + KEY + " \",\"depositCents\":3000,\"depositRule\":\"NEW_CUSTOMERS\"}").andExpect(status().isOk()).andReturn().getResponse()
				.getContentAsString();
		assertEquals(KEY, JsonPath.read(ok, "$.pixKey"), "a chave é aparada");
		assertEquals(3000, (int) JsonPath.read(ok, "$.depositCents"));
		assertEquals("NEW_CUSTOMERS", JsonPath.read(ok, "$.depositRule"));

		configure(tokenA, "{\"pixKey\":\"" + KEY + "\",\"depositCents\":3000,\"depositRule\":\"SEMPRE\"}").andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.depositRule").exists());
		configure(tokenA, "{\"depositCents\":3000,\"depositRule\":\"NEW_CUSTOMERS\"}").andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.pixKey").exists());
		configure(tokenA, "{\"pixKey\":\"" + KEY + "\",\"depositRule\":\"NEW_CUSTOMERS\"}").andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.depositCents").exists());
		configure(tokenA, "{\"pixKey\":\"" + KEY + "\",\"depositCents\":0,\"depositRule\":\"NEVER\"}").andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.depositCents").exists());
		configure(tokenA, "{\"pixKey\":\"" + KEY + "\",\"depositCents\":10000001,\"depositRule\":\"NEVER\"}").andExpect(status().isBadRequest());
		String longKey = "a".repeat(141);
		String response = configure(tokenA, "{\"pixKey\":\"" + longKey + "\",\"depositCents\":3000,\"depositRule\":\"NEVER\"}").andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.pixKey").exists()).andReturn().getResponse().getContentAsString();
		assertFalse(response.contains(longKey), "o erro nunca repete a chave");
		configure(tokenA, "{\"pixKey\":\"a\\nb\",\"depositCents\":3000,\"depositRule\":\"NEVER\"}").andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.pixKey").exists());
		assertEquals("NEW_CUSTOMERS", jdbc.queryForObject("SELECT deposit_rule FROM providers WHERE id = ?", String.class, providerA), "recusas não mudam o que estava salvo");
		// Desligar mantém chave e valor (o estúdio liga de novo sem redigitar); chave vazia com NEVER limpa a chave.
		configure(tokenA, "{\"pixKey\":\"" + KEY + "\",\"depositCents\":3000,\"depositRule\":\"NEVER\"}").andExpect(status().isOk()).andExpect(jsonPath("$.pixKey").value(KEY));
		configure(tokenA, "{\"pixKey\":\"\",\"depositRule\":\"NEVER\"}").andExpect(status().isOk()).andExpect(jsonPath("$.pixKey").doesNotExist());
	}

	@Test
	void soODonoConfigura_tutorEOutroEstudioNaoMexem() throws Exception {
		configure(tokenTutor, "{\"pixKey\":\"" + KEY + "\",\"depositCents\":3000,\"depositRule\":\"NEW_CUSTOMERS\"}").andExpect(status().isForbidden());
		configure(tokenB, "{\"pixKey\":\"" + KEY + "\",\"depositCents\":3000,\"depositRule\":\"NEW_CUSTOMERS\"}").andExpect(status().isOk());
		assertEquals("NEVER", jdbc.queryForObject("SELECT deposit_rule FROM providers WHERE id = ?", String.class, providerA), "configurar o estúdio B não mexe no A");
	}

	@Test
	void bancoRecusaRegraLigadaSemChaveOuValorEValorForaDaFaixa() {
		assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("UPDATE providers SET deposit_rule = 'NEW_CUSTOMERS' WHERE id = ?", providerA));
		assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("UPDATE providers SET pix_key = 'x', deposit_cents = 0 WHERE id = ?", providerA));
		assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("UPDATE providers SET deposit_rule = 'QUALQUER' WHERE id = ?", providerA));
	}

	@Test
	void clienteNovoRecebePedidoDeSinalComValorCongeladoEChaveSoEnquantoPendente() throws Exception {
		rule("NEW_CUSTOMERS");
		Map<String, Object> t = JsonPath.read(terms(tokenTutor), "$");
		assertEquals(Map.of("required", true, "depositCents", 3000, "pixKey", KEY), t, "o tutor vê o que deve e a chave; nenhum dado de faltas");
		String booked = book(tokenTutor, petOfTutor, 3);
		assertEquals("REQUESTED", JsonPath.read(booked, "$.depositStatus"));
		assertEquals(3000, (int) JsonPath.read(booked, "$.depositCents"));
		assertEquals(KEY, JsonPath.read(booked, "$.studioPixKey"));
		// Mudar o valor depois não reescreve o que já foi pedido.
		configure(tokenA, "{\"pixKey\":\"" + KEY + "\",\"depositCents\":5000,\"depositRule\":\"NEW_CUSTOMERS\"}").andExpect(status().isOk());
		mvc.perform(get("/api/appointments/mine").header(HttpHeaders.AUTHORIZATION, tokenTutor)).andExpect(jsonPath("$[0].depositCents").value(3000));
		assertEquals(3000, jdbc.queryForObject("SELECT deposit_cents FROM appointments", Integer.class));
		// A chave nunca vai no catálogo aberto.
		String catalog = mvc.perform(get("/api/catalog/providers").header(HttpHeaders.AUTHORIZATION, tokenTutor)).andReturn().getResponse().getContentAsString();
		assertFalse(catalog.contains(KEY) || catalog.contains("pixKey"));
	}

	@Test
	void quemJaFoiAtendidoNoEstudioNaoPagaSinalDeNovato() throws Exception {
		rule("NEW_CUSTOMERS");
		history("COMPLETED", 1);
		assertEquals(false, JsonPath.read(terms(tokenTutor), "$.required"));
		assertNull(JsonPath.read(terms(tokenTutor), "$.pixKey"), "sem sinal pedido, a chave também não sai");
		assertEquals("NONE", JsonPath.read(book(tokenTutor, petOfTutor, 3), "$.depositStatus"));
		// Outro tutor, sem histórico, continua novato.
		assertEquals(true, JsonPath.read(terms(tokenOther), "$.required"));
	}

	@Test
	void duasFaltasNesteEstudioPedemSinalSoNaRegraComFaltas() throws Exception {
		history("COMPLETED", 1);
		history("NO_SHOW", 1);
		rule("NEW_OR_REPEAT_NO_SHOW");
		assertEquals(false, JsonPath.read(terms(tokenTutor), "$.required"), "uma falta ainda não pede");
		history("NO_SHOW", 1);
		assertEquals(true, JsonPath.read(terms(tokenTutor), "$.required"), "duas faltas pedem");
		assertEquals("REQUESTED", JsonPath.read(book(tokenTutor, petOfTutor, 3), "$.depositStatus"));
		rule("NEW_CUSTOMERS");
		assertEquals(false, JsonPath.read(terms(tokenTutor), "$.required"), "a regra só de novatos não olha as faltas");
	}

	@Test
	void faltasEmOutroEstudioNaoContam() throws Exception {
		history("COMPLETED", 1);
		UUID serviceB = jdbc.queryForObject("INSERT INTO services (provider_id, name, duration_minutes) VALUES (?, 'Banho', 30) RETURNING id", UUID.class, providerB);
		for (int i = 0; i < 3; i++) {
			OffsetDateTime start = OffsetDateTime.now().minusDays(40 + i).withSecond(0).withNano(0);
			jdbc.update("INSERT INTO appointments (provider_id, service_id, customer_id, starts_at, ends_at, status, pet_id, pet_name, pet_species) VALUES (?, ?, ?, ?, ?, 'NO_SHOW', ?, 'Rex', 'DOG')",
					providerB, serviceB, tutor, start, start.plusMinutes(30), petOfTutor);
		}
		rule("NEW_OR_REPEAT_NO_SHOW");
		assertEquals(false, JsonPath.read(terms(tokenTutor), "$.required"));
	}

	@Test
	void equipeMarcaRecebido_umaVez_semMudarOAtendimento_eAgendaMostra() throws Exception {
		rule("NEW_CUSTOMERS");
		String booked = book(tokenTutor, petOfTutor, 3);
		String id = JsonPath.read(booked, "$.id");
		LocalDate day = OffsetDateTime.parse(JsonPath.<String>read(booked, "$.startsAt")).atZoneSameInstant(SP).toLocalDate();
		String before = mvc.perform(get("/api/agenda?date=" + day).header(HttpHeaders.AUTHORIZATION, tokenA)).andReturn().getResponse().getContentAsString();
		assertEquals("REQUESTED", JsonPath.read(before, "$[0].depositStatus"));
		assertEquals(3000, (int) JsonPath.read(before, "$[0].depositCents"));

		mvc.perform(post("/api/appointments/" + id + "/deposit-received").header(HttpHeaders.AUTHORIZATION, tokenTutor)).andExpect(status().isForbidden());
		mvc.perform(post("/api/appointments/" + id + "/deposit-received").header(HttpHeaders.AUTHORIZATION, tokenB)).andExpect(status().isNotFound());
		mvc.perform(post("/api/appointments/" + id + "/deposit-received").header(HttpHeaders.AUTHORIZATION, tokenA)).andExpect(status().isOk()).andExpect(jsonPath("$.depositStatus").value("RECEIVED"))
				.andExpect(jsonPath("$.studioPixKey").doesNotExist()).andExpect(jsonPath("$.status").value("SCHEDULED"));
		assertNotNull(jdbc.queryForObject("SELECT deposit_received_at FROM appointments WHERE id = ?::uuid", Object.class, id));
		mvc.perform(post("/api/appointments/" + id + "/deposit-received").header(HttpHeaders.AUTHORIZATION, tokenA)).andExpect(status().isConflict()).andExpect(jsonPath("$.reason").value("INVALID_STATE"));
		mvc.perform(get("/api/appointments/mine").header(HttpHeaders.AUTHORIZATION, tokenTutor)).andExpect(jsonPath("$[0].depositStatus").value("RECEIVED")).andExpect(jsonPath("$[0].studioPixKey").doesNotExist());
		String after = mvc.perform(get("/api/agenda?date=" + day).header(HttpHeaders.AUTHORIZATION, tokenA)).andReturn().getResponse().getContentAsString();
		assertEquals("RECEIVED", JsonPath.read(after, "$[0].depositStatus"));
	}

	@Test
	void semSinalPedidoOuCancelado_naoHaOQueMarcar() throws Exception {
		String free = JsonPath.read(book(tokenTutor, petOfTutor, 3), "$.id"); // regra NEVER: sem sinal
		mvc.perform(post("/api/appointments/" + free + "/deposit-received").header(HttpHeaders.AUTHORIZATION, tokenA)).andExpect(status().isConflict()).andExpect(jsonPath("$.reason").value("INVALID_STATE"));
		rule("NEW_CUSTOMERS");
		String asked = JsonPath.read(book(tokenOther, jdbc.queryForObject("INSERT INTO pets (customer_id, name, species) VALUES (?, 'Mel', 'CAT') RETURNING id", UUID.class, otherTutor), 4), "$.id");
		mvc.perform(post("/api/appointments/" + asked + "/cancel").header(HttpHeaders.AUTHORIZATION, tokenA)).andExpect(status().isOk());
		mvc.perform(post("/api/appointments/" + asked + "/deposit-received").header(HttpHeaders.AUTHORIZATION, tokenA)).andExpect(status().isConflict()).andExpect(jsonPath("$.reason").value("ALREADY_CANCELLED"));
	}

	@Test
	void bancoGarantiaConsistenciaDoSinalNoAgendamento() throws Exception {
		rule("NEW_CUSTOMERS");
		String id = JsonPath.read(book(tokenTutor, petOfTutor, 3), "$.id");
		assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("UPDATE appointments SET deposit_status = 'RECEIVED' WHERE id = ?::uuid", id), "recebido exige a data");
		assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("UPDATE appointments SET deposit_status = 'NONE' WHERE id = ?::uuid", id), "sem sinal, sem valor");
		assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("UPDATE appointments SET deposit_status = 'TALVEZ' WHERE id = ?::uuid", id));
	}
}
