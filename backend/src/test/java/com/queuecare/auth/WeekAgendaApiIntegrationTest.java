package com.queuecare.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Semana do prestador (GET /api/agenda/week): 7 dias CIVIS no fuso do prestador, com as mesmas regras e o mesmo isolamento da agenda
 * do dia. Os agendamentos dos casos de limite são gravados direto no banco (a API de reserva limita a data de agendamento), mas a
 * LEITURA passa por tokens, filtros e PostgreSQL reais.
 */
class WeekAgendaApiIntegrationTest extends AuthIntegrationTestBase {

	UUID ownerA, ownerB, attendantA, customer;
	UUID providerA, providerB, serviceA, serviceB;
	String tokenA, tokenB, tokenAttendantA, tokenCustomer;

	@BeforeEach
	void seed() throws Exception {
		ownerA = seedUser("wa@exemplo.test", "PROVIDER", true);
		ownerB = seedUser("wb@exemplo.test", "PROVIDER", true);
		attendantA = seedUser("wat@exemplo.test", "ATTENDANT", true);
		customer = seedUser("wc@exemplo.test", "CUSTOMER", true);
		providerA = business(ownerA, "Negócio A", "America/Sao_Paulo");
		providerB = business(ownerB, "Negócio B", "America/Sao_Paulo");
		serviceA = service(providerA);
		serviceB = service(providerB);
		tokenA = tokenOf("wa@exemplo.test");
		tokenB = tokenOf("wb@exemplo.test");
		tokenAttendantA = tokenOf("wat@exemplo.test");
		tokenCustomer = tokenOf("wc@exemplo.test");
		mvc.perform(post("/api/provider/staff").header(HttpHeaders.AUTHORIZATION, tokenA).contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"wat@exemplo.test\"}")).andExpect(status().isCreated());
	}

	private UUID business(UUID owner, String name, String zone) {
		return jdbc.queryForObject("INSERT INTO providers (user_id, business_name, time_zone, minimum_notice_minutes, cancellation_deadline_minutes) "
				+ "VALUES (?, ?, ?, 0, 120) RETURNING id", UUID.class, owner, name, zone);
	}

	private UUID service(UUID provider) {
		return jdbc.queryForObject("INSERT INTO services (provider_id, name, duration_minutes) VALUES (?, 'Serviço', 60) RETURNING id", UUID.class, provider);
	}

	private String tokenOf(String email) throws Exception {
		String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(json("email", email, "password", PASSWORD)))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		return "Bearer " + JsonPath.read(body, "$.accessToken");
	}

	/** Grava um agendamento de 1 hora que começa no horário LOCAL dado (fuso do prestador). */
	private String appointment(UUID provider, UUID service, String zone, String localDateTime, String statusName) {
		ZonedDateTime start = java.time.LocalDateTime.parse(localDateTime).atZone(ZoneId.of(zone));
		return jdbc.queryForObject("INSERT INTO appointments (provider_id, service_id, customer_id, starts_at, ends_at, status) "
				+ "VALUES (?, ?, ?, ?, ?, ?) RETURNING id::text", String.class, provider, service, customer, java.sql.Timestamp.from(start.toInstant()),
				java.sql.Timestamp.from(start.plusHours(1).toInstant()), statusName);
	}

	private ResultActions week(String bearer, String start) throws Exception {
		return mvc.perform(get("/api/agenda/week").param("start", start).header(HttpHeaders.AUTHORIZATION, bearer));
	}

	private List<String> ids(String bearer, String start) throws Exception {
		String body = week(bearer, start).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		return JsonPath.read(body, "$[*].id");
	}

	@Test
	void semanaTemSetePrimeirosDiasCivisOrdenadaSemCanceladosELimitesExatos() throws Exception {
		String before = appointment(providerA, serviceA, "America/Sao_Paulo", "2027-02-28T23:00:00", "SCHEDULED"); // 23h do dia anterior: fora
		String first = appointment(providerA, serviceA, "America/Sao_Paulo", "2027-03-01T00:00:00", "SCHEDULED"); // primeiro instante: dentro
		String middle = appointment(providerA, serviceA, "America/Sao_Paulo", "2027-03-03T10:00:00", "CHECKED_IN");
		String last = appointment(providerA, serviceA, "America/Sao_Paulo", "2027-03-07T23:00:00", "COMPLETED"); // última hora do 7º dia: dentro
		String next = appointment(providerA, serviceA, "America/Sao_Paulo", "2027-03-08T00:00:00", "SCHEDULED"); // 8º dia: fora
		appointment(providerA, serviceA, "America/Sao_Paulo", "2027-03-02T09:00:00", "CANCELLED"); // cancelado: fora

		assertEquals(List.of(first, middle, last), ids(tokenA, "2027-03-01"));
		assertEquals(List.of(before), ids(tokenA, "2027-02-22"), "a semana anterior termina exatamente onde esta começa");
		assertEquals(List.of(), ids(tokenA, "2027-04-05"), "semana vazia");
		// a semana seguinte começa exatamente onde esta termina
		assertEquals(List.of(next), ids(tokenA, "2027-03-08"));
	}

	@Test
	void viradaDeMesEDeAnoNaMesmaSemana() throws Exception {
		String dec = appointment(providerA, serviceA, "America/Sao_Paulo", "2026-12-31T22:00:00", "SCHEDULED");
		String jan = appointment(providerA, serviceA, "America/Sao_Paulo", "2027-01-02T08:00:00", "SCHEDULED");
		assertEquals(List.of(dec, jan), ids(tokenA, "2026-12-30"));
	}

	@Test
	void horarioDeVeraoNaSemanaNaoPerdeNemDuplicaAgendamentos() throws Exception {
		jdbc.update("UPDATE providers SET time_zone = 'America/New_York' WHERE id = ?", providerA);
		// 14/03/2027 é o dia em que os EUA adiantam o relógio (o dia tem 23 horas); a semana de 08 a 14/03 termina nele.
		String monday = appointment(providerA, serviceA, "America/New_York", "2027-03-08T00:00:00", "SCHEDULED");
		String sundayLate = appointment(providerA, serviceA, "America/New_York", "2027-03-14T23:00:00", "SCHEDULED"); // última hora do dia de 23 h
		String after = appointment(providerA, serviceA, "America/New_York", "2027-03-15T00:00:00", "SCHEDULED");
		assertEquals(List.of(monday, sundayLate), ids(tokenA, "2027-03-08"));
		assertEquals(List.of(after), ids(tokenA, "2027-03-15"));
	}

	@Test
	void donoEAtendenteVinculadoVeemSoOPrestadorDeles() throws Exception {
		String mine = appointment(providerA, serviceA, "America/Sao_Paulo", "2027-03-02T10:00:00", "SCHEDULED");
		String other = appointment(providerB, serviceB, "America/Sao_Paulo", "2027-03-02T10:00:00", "SCHEDULED");
		assertEquals(List.of(mine), ids(tokenA, "2027-03-01"));
		assertEquals(List.of(mine), ids(tokenAttendantA, "2027-03-01"));
		assertEquals(List.of(other), ids(tokenB, "2027-03-01"));
	}

	@Test
	void parametroProviderIdEIgnoradoNuncaTrocaOContexto() throws Exception {
		appointment(providerA, serviceA, "America/Sao_Paulo", "2027-03-02T10:00:00", "SCHEDULED");
		String other = appointment(providerB, serviceB, "America/Sao_Paulo", "2027-03-02T11:00:00", "SCHEDULED");
		String body = mvc.perform(get("/api/agenda/week").param("start", "2027-03-01").param("providerId", providerB.toString())
				.header(HttpHeaders.AUTHORIZATION, tokenA)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		List<String> ids = JsonPath.read(body, "$[*].id");
		assertEquals(1, ids.size());
		assertEquals(false, ids.contains(other));
	}

	@Test
	void clienteSemTokenEAtendenteSemVinculoNaoVeem() throws Exception {
		String solto = tokenOf2("solto-w@exemplo.test", "ATTENDANT");
		week(tokenCustomer, "2027-03-01").andExpect(status().isForbidden());
		week(solto, "2027-03-01").andExpect(status().isForbidden());
		mvc.perform(get("/api/agenda/week").param("start", "2027-03-01")).andExpect(status().isUnauthorized());
	}

	@Test
	void prestadorSemNegocioRecebeNaoConfigurado() throws Exception {
		String t = tokenOf2("semneg-w@exemplo.test", "PROVIDER");
		week(t, "2027-03-01").andExpect(status().isNotFound()).andExpect(jsonPath("$.reason").value("PROVIDER_NOT_CONFIGURED"));
	}

	@Test
	void dataInvalidaOuAusenteEhRecusada() throws Exception {
		week(tokenA, "2027-13-45").andExpect(status().isBadRequest());
		mvc.perform(get("/api/agenda/week").header(HttpHeaders.AUTHORIZATION, tokenA)).andExpect(status().isBadRequest());
	}

	@Test
	void naoVazaDadosPessoaisDoCliente() throws Exception {
		appointment(providerA, serviceA, "America/Sao_Paulo", "2027-03-02T10:00:00", "SCHEDULED");
		week(tokenA, "2027-03-01").andExpect(status().isOk())
				.andExpect(jsonPath("$[0].customerName").value("Fulano de Teste"))
				.andExpect(jsonPath("$[0].customerEmail").doesNotExist())
				.andExpect(jsonPath("$[0].email").doesNotExist())
				.andExpect(jsonPath("$[0].passwordHash").doesNotExist());
	}

	private String tokenOf2(String email, String role) throws Exception {
		seedUser(email, role, true);
		return tokenOf(email);
	}
}
