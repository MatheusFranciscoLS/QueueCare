package com.queuecare.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

/**
 * Telefone do tutor (ADR 0019, fatia 1): cadastro, troca, formato garantido pelo banco e o que a equipe vê. A equipe só enxerga o telefone
 * de quem tem agendamento no SEU prestador, na agenda do dia e na fila; a semana, outro prestador, outro cliente e o administrador não.
 */
class PhoneApiIntegrationTest extends AuthIntegrationTestBase {

	UUID ownerA, ownerB, customer, otherCustomer;
	UUID providerA, serviceA;
	String tokenA, tokenB, tokenCustomer, tokenOtherCustomer, tokenAdmin;

	@BeforeEach
	void seed() throws Exception {
		ownerA = seedUser("pa@exemplo.test", "PROVIDER", true);
		ownerB = seedUser("pb@exemplo.test", "PROVIDER", true);
		customer = seedUser("pc@exemplo.test", "CUSTOMER", true);
		otherCustomer = seedUser("po@exemplo.test", "CUSTOMER", true);
		seedUser("padm@exemplo.test", "ADMIN", true);
		providerA = jdbc.queryForObject("INSERT INTO providers (user_id, business_name, time_zone, minimum_notice_minutes, cancellation_deadline_minutes) "
				+ "VALUES (?, 'Negócio A', 'America/Sao_Paulo', 0, 120) RETURNING id", UUID.class, ownerA);
		jdbc.update("INSERT INTO providers (user_id, business_name, time_zone, minimum_notice_minutes, cancellation_deadline_minutes) VALUES (?, 'Negócio B', 'America/Sao_Paulo', 0, 120)", ownerB);
		serviceA = jdbc.queryForObject("INSERT INTO services (provider_id, name, duration_minutes) VALUES (?, 'Banho', 60) RETURNING id", UUID.class, providerA);
		tokenA = tokenOf("pa@exemplo.test");
		tokenB = tokenOf("pb@exemplo.test");
		tokenCustomer = tokenOf("pc@exemplo.test");
		tokenOtherCustomer = tokenOf("po@exemplo.test");
		tokenAdmin = tokenOf("padm@exemplo.test");
	}

	private String tokenOf(String email) throws Exception {
		String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(json("email", email, "password", PASSWORD)))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		return "Bearer " + JsonPath.read(body, "$.accessToken");
	}

	private void setPhone(String bearer, String phone) throws Exception {
		mvc.perform(put("/api/me/phone").header(HttpHeaders.AUTHORIZATION, bearer).contentType(MediaType.APPLICATION_JSON).content(json("phone", phone)))
				.andExpect(status().isOk());
	}

	private String book(String startLocal, String statusName) {
		ZonedDateTime start = java.time.LocalDateTime.parse(startLocal).atZone(ZoneId.of("America/Sao_Paulo"));
		return jdbc.queryForObject("INSERT INTO appointments (provider_id, service_id, customer_id, starts_at, ends_at, status, pet_id, pet_name, pet_species) "
				+ "VALUES (?, ?, ?, ?, ?, ?, ?, 'Pet de Teste', 'DOG') RETURNING id::text", String.class, providerA, serviceA, customer, java.sql.Timestamp.from(start.toInstant()),
				java.sql.Timestamp.from(start.plusHours(1).toInstant()), statusName, petOf(customer));
	}

	@Test
	void cadastroGuardaOTelefoneNormalizadoESemTelefoneContinuaValendo() throws Exception {
		mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
				.content(json("name", "Ana", "email", "ana@exemplo.test", "password", PASSWORD, "phone", "(11) 91234-5678")))
				.andExpect(status().isCreated()).andExpect(jsonPath("$.phone").value("5511912345678"));
		assertEquals("5511912345678", jdbc.queryForObject("SELECT phone FROM users WHERE email = 'ana@exemplo.test'", String.class));
		mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(json("name", "Bia", "email", "bia@exemplo.test", "password", PASSWORD)))
				.andExpect(status().isCreated()).andExpect(jsonPath("$.phone").doesNotExist());
	}

	@Test
	void cadastroComTelefoneInvalidoEh400SemRepetirOValor() throws Exception {
		String body = mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
				.content(json("name", "Ana", "email", "ana2@exemplo.test", "password", PASSWORD, "phone", "999-secreto")))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.phone").exists()).andReturn().getResponse().getContentAsString();
		assertFalse(body.contains("999-secreto"), "a resposta nunca repete o telefone recebido");
		assertEquals(0, count("SELECT count(*) FROM users WHERE email = 'ana2@exemplo.test'"), "nada é gravado");
	}

	@Test
	void trocarORemoverOProprioTelefoneEMeDevolve() throws Exception {
		setPhone(tokenCustomer, "11 91234-5678");
		mvc.perform(get("/api/me").header(HttpHeaders.AUTHORIZATION, tokenCustomer)).andExpect(jsonPath("$.phone").value("5511912345678"));
		setPhone(tokenCustomer, "");
		mvc.perform(get("/api/me").header(HttpHeaders.AUTHORIZATION, tokenCustomer)).andExpect(jsonPath("$.phone").doesNotExist());
		mvc.perform(put("/api/me/phone").header(HttpHeaders.AUTHORIZATION, tokenCustomer).contentType(MediaType.APPLICATION_JSON).content(json("phone", "123")))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.phone").exists());
	}

	@Test
	void trocarOTelefoneExigeLoginEMexeSoNaPropriaConta() throws Exception {
		mvc.perform(put("/api/me/phone").contentType(MediaType.APPLICATION_JSON).content(json("phone", "11912345678"))).andExpect(status().isUnauthorized());
		setPhone(tokenCustomer, "11912345678");
		assertEquals(null, jdbc.queryForObject("SELECT phone FROM users WHERE id = ?", String.class, otherCustomer), "a conta dos outros não muda");
	}

	@Test
	void bancoRecusaTelefoneForaDoFormato() {
		for (String bad : new String[] { "11912345678", "+5511912345678", "5501912345678", "551191234567a", "55119123456789012", "5511 912345678" }) {
			assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("UPDATE users SET phone = ? WHERE id = ?", bad, customer), bad);
		}
		jdbc.update("UPDATE users SET phone = '5511912345678' WHERE id = ?", customer);
	}

	@Test
	void equipeVeOTelefoneNaAgendaDoDiaMasNaoNaSemana() throws Exception {
		setPhone(tokenCustomer, "(11) 91234-5678");
		book("2027-03-02T10:00:00", "SCHEDULED");
		String day = mvc.perform(get("/api/agenda").param("date", "2027-03-02").header(HttpHeaders.AUTHORIZATION, tokenA)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		assertEquals("5511912345678", JsonPath.read(day, "$[0].customerPhone"));
		String week = mvc.perform(get("/api/agenda/week").param("start", "2027-03-01").header(HttpHeaders.AUTHORIZATION, tokenA)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		assertFalse(week.contains("5511912345678"), "a semana não leva o telefone");
	}

	@Test
	void filaTrazOTelefoneDeQuemChegouHoje() throws Exception {
		setPhone(tokenCustomer, "(11) 91234-5678");
		ZonedDateTime now = ZonedDateTime.now(ZoneId.of("America/Sao_Paulo"));
		String id = book(now.toLocalDate() + "T" + String.format("%02d:00:00", Math.min(now.getHour(), 22)), "CHECKED_IN");
		jdbc.update("INSERT INTO appointment_events (appointment_id, event_type, actor_id) VALUES (?::uuid, 'CHECKED_IN', ?)", id, ownerA);
		mvc.perform(get("/api/agenda/queue").header(HttpHeaders.AUTHORIZATION, tokenA)).andExpect(status().isOk()).andExpect(jsonPath("$[0].customerPhone").value("5511912345678"));
	}

	@Test
	void outroPrestadorEOAdministradorNaoRecebemOTelefone() throws Exception {
		setPhone(tokenCustomer, "(11) 91234-5678");
		book("2027-03-02T10:00:00", "SCHEDULED");
		String other = mvc.perform(get("/api/agenda").param("date", "2027-03-02").header(HttpHeaders.AUTHORIZATION, tokenB)).andReturn().getResponse().getContentAsString();
		assertFalse(other.contains("5511912345678"));
		mvc.perform(get("/api/agenda").param("date", "2027-03-02").header(HttpHeaders.AUTHORIZATION, tokenAdmin)).andExpect(status().isForbidden());
		mvc.perform(get("/api/agenda").param("date", "2027-03-02").header(HttpHeaders.AUTHORIZATION, tokenOtherCustomer)).andExpect(status().isForbidden());
	}
}
