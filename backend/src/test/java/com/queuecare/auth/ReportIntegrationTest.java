package com.queuecare.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/**
 * O resumo do estúdio (ADR 0031): contagens por situação, valores com nome honesto (concluído, previsto, sinais recebidos), por serviço, por dia no FUSO do estúdio,
 * faltas por tutor e clientes que sumiram. Só o dono, só do próprio estúdio.
 */
class ReportIntegrationTest extends AuthIntegrationTestBase {

	private static final ZoneId SP = ZoneId.of("America/Sao_Paulo");

	UUID ownerA, ownerB, attendant, joana, pedro, ana, inativo;
	UUID providerA, providerB, banho, tosa, banhoB;
	String tokenA, tokenB, tokenAttendant, tokenJoana;
	private int seq;

	@BeforeEach
	void seed() throws Exception {
		ownerA = seedUser("ra@exemplo.test", "PROVIDER", true);
		ownerB = seedUser("rb@exemplo.test", "PROVIDER", true);
		attendant = seedUser("rat@exemplo.test", "ATTENDANT", true);
		joana = seedUser("rj@exemplo.test", "CUSTOMER", true);
		pedro = seedUser("rp@exemplo.test", "CUSTOMER", true);
		ana = seedUser("rn@exemplo.test", "CUSTOMER", true);
		inativo = seedUser("ri@exemplo.test", "CUSTOMER", false);
		jdbc.update("UPDATE users SET name = 'Joana Lima', phone = '5511987654321' WHERE id = ?", joana);
		jdbc.update("UPDATE users SET name = 'Pedro Alves' WHERE id = ?", pedro);
		jdbc.update("UPDATE users SET name = 'Ana Souza' WHERE id = ?", ana);
		jdbc.update("UPDATE users SET name = 'Inativo' WHERE id = ?", inativo);
		providerA = business(ownerA, "Estúdio A");
		providerB = business(ownerB, "Estúdio B");
		jdbc.update("INSERT INTO provider_staff (provider_id, user_id) VALUES (?, ?)", providerA, attendant);
		banho = service(providerA, "Banho");
		tosa = service(providerA, "Tosa");
		banhoB = service(providerB, "Banho");
		tokenA = tokenOf("ra@exemplo.test");
		tokenB = tokenOf("rb@exemplo.test");
		tokenAttendant = tokenOf("rat@exemplo.test");
		tokenJoana = tokenOf("rj@exemplo.test");
	}

	private UUID business(UUID owner, String name) {
		return jdbc.queryForObject("INSERT INTO providers (user_id, business_name, time_zone, minimum_notice_minutes, cancellation_deadline_minutes) VALUES (?, ?, 'America/Sao_Paulo', 0, 120) RETURNING id",
				UUID.class, owner, name);
	}

	private UUID service(UUID provider, String name) {
		return jdbc.queryForObject("INSERT INTO services (provider_id, name, duration_minutes) VALUES (?, ?, 30) RETURNING id", UUID.class, provider, name);
	}

	private String tokenOf(String email) throws Exception {
		String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(json("email", email, "password", PASSWORD))).andExpect(status().isOk()).andReturn()
				.getResponse().getContentAsString();
		return "Bearer " + JsonPath.read(body, "$.accessToken");
	}

	private static LocalDate day(int offset) {
		return LocalDate.now(SP).plusDays(offset);
	}

	/** Agendamento direto no banco, no horário LOCAL do estúdio ({@code at}), com o valor e o estado dados; cada um num minuto distinto para não bater na exclusão. */
	private void appt(UUID provider, UUID service, UUID customer, int dayOffset, LocalTime at, String statusName, Integer cents, String depositStatus, Integer depositCents) {
		UUID pet = jdbc.queryForObject("INSERT INTO pets (customer_id, name, species) VALUES (?, 'Pet', 'DOG') RETURNING id", UUID.class, customer);
		OffsetDateTime start = day(dayOffset).atTime(at).plusMinutes(++seq).atZone(SP).toOffsetDateTime();
		jdbc.update("INSERT INTO appointments (provider_id, service_id, customer_id, starts_at, ends_at, status, price_cents, deposit_status, deposit_cents, deposit_received_at, pet_id, pet_name, pet_species) "
				+ "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, CASE WHEN ? = 'RECEIVED' THEN now() END, ?, 'Pet', 'DOG')", provider, service, customer, start, start.plusMinutes(20), statusName, cents, depositStatus == null ? "NONE" : depositStatus, depositCents, depositStatus == null ? "NONE" : depositStatus, pet);
	}

	private ResultActions summary(String bearer, int from, int to) throws Exception {
		return mvc.perform(get("/api/provider/reports/summary").param("from", day(from).toString()).param("to", day(to).toString()).header(HttpHeaders.AUTHORIZATION, bearer));
	}

	@Test
	void contaPorSituacaoEDaOsValoresComONomeCerto() throws Exception {
		appt(providerA, banho, joana, -3, LocalTime.of(10, 0), "COMPLETED", 5000, null, null);
		appt(providerA, banho, pedro, -2, LocalTime.of(10, 0), "COMPLETED", 5000, null, null);
		appt(providerA, tosa, joana, -2, LocalTime.of(14, 0), "COMPLETED", 4500, null, null);
		appt(providerA, banho, pedro, -1, LocalTime.of(10, 0), "NO_SHOW", 5000, null, null);
		appt(providerA, banho, pedro, -1, LocalTime.of(15, 0), "NO_SHOW", 5000, null, null);
		appt(providerA, tosa, ana, -1, LocalTime.of(16, 0), "CANCELLED", 4500, null, null);
		appt(providerA, banho, ana, 2, LocalTime.of(10, 0), "SCHEDULED", 5000, "RECEIVED", 3000);
		appt(providerA, tosa, joana, 0, LocalTime.of(17, 0), "CHECKED_IN", 4500, null, null);
		appt(providerB, banhoB, joana, -2, LocalTime.of(10, 0), "COMPLETED", 99999, null, null); // de outro estúdio: não conta

		String body = summary(tokenA, -5, 3).andExpect(status().isOk()).andExpect(header().string(HttpHeaders.CACHE_CONTROL, org.hamcrest.Matchers.containsString("no-store"))).andReturn()
				.getResponse().getContentAsString();

		assertEquals(3, (int) JsonPath.read(body, "$.counts.completed"));
		assertEquals(2, (int) JsonPath.read(body, "$.counts.noShow"));
		assertEquals(1, (int) JsonPath.read(body, "$.counts.cancelled"));
		assertEquals(1, (int) JsonPath.read(body, "$.counts.scheduled"));
		assertEquals(1, (int) JsonPath.read(body, "$.counts.inProgress"));
		assertEquals(14500, (int) JsonPath.read(body, "$.amounts.completedCents"), "só os concluídos entram no valor atendido");
		assertEquals(9500, (int) JsonPath.read(body, "$.amounts.expectedCents"), "marcado (5000) + em andamento (4500); falta e cancelado nunca entram");
		assertEquals(3000, (int) JsonPath.read(body, "$.amounts.depositsReceivedCents"));

		assertEquals(List.of("Banho", "Tosa"), JsonPath.read(body, "$.byService[*].service"));
		assertEquals(List.of(2, 1), JsonPath.read(body, "$.byService[*].completed"));
		assertEquals(List.of(10000, 4500), JsonPath.read(body, "$.byService[*].completedCents"));

		assertEquals(9, ((List<?>) JsonPath.read(body, "$.byDay")).size(), "todos os dias do período, inclusive os sem atendimento");
		assertEquals(1, (int) JsonPath.read(body, "$.byDay[2].completed")); // dia -3
		assertEquals(2, (int) JsonPath.read(body, "$.byDay[3].completed")); // dia -2
		assertEquals(2, (int) JsonPath.read(body, "$.byDay[4].noShow")); // dia -1
		assertEquals(0, (int) JsonPath.read(body, "$.byDay[0].completed"));

		assertEquals(List.of("Pedro Alves"), JsonPath.read(body, "$.topNoShows[*].name"));
		assertEquals(2, (int) JsonPath.read(body, "$.topNoShows[0].noShows"));
	}

	@Test
	void odiaEODoFusoDoEstudioNaoODoServidor() throws Exception {
		// 23:30 locais do dia -2 ficam no dia SEGUINTE em UTC (02:30Z): têm de contar no dia local
		appt(providerA, banho, joana, -2, LocalTime.of(23, 30), "COMPLETED", 5000, null, null);
		String body = summary(tokenA, -3, -1).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		assertEquals(0, (int) JsonPath.read(body, "$.byDay[0].completed"));
		assertEquals(1, (int) JsonPath.read(body, "$.byDay[1].completed"));
		assertEquals(0, (int) JsonPath.read(body, "$.byDay[2].completed"));
		assertEquals(day(-2).toString(), JsonPath.read(body, "$.byDay[1].date"));
	}

	@Test
	void periodoSemAtendimentoDevolveZerosEUmaSerieCompleta() throws Exception {
		String body = summary(tokenA, -2, 0).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		assertEquals(0, (int) JsonPath.read(body, "$.counts.completed"));
		assertEquals(0, (int) JsonPath.read(body, "$.amounts.completedCents"));
		assertEquals(3, ((List<?>) JsonPath.read(body, "$.byDay")).size());
		assertEquals(0, ((List<?>) JsonPath.read(body, "$.byService")).size());
	}

	@Test
	void validaOPeriodo() throws Exception {
		summary(tokenA, 2, 0).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.dates").exists());
		summary(tokenA, -400, 0).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.dates").exists());
		mvc.perform(get("/api/provider/reports/summary").header(HttpHeaders.AUTHORIZATION, tokenA)).andExpect(status().isBadRequest());
		summary(tokenA, -366, 0).andExpect(status().isOk()); // 367 dias corridos incluindo as pontas: o limite é 366 de distância
	}

	@Test
	void soODonoDoEstudioLeEUmEstudioNaoVeOOutro() throws Exception {
		appt(providerA, banho, joana, -1, LocalTime.of(10, 0), "COMPLETED", 5000, null, null);
		summary(tokenAttendant, -1, 0).andExpect(status().isForbidden()); // tem valores e nomes: só o dono
		summary(tokenJoana, -1, 0).andExpect(status().isForbidden());
		mvc.perform(get("/api/provider/reports/summary").param("from", day(-1).toString()).param("to", day(0).toString())).andExpect(status().isUnauthorized());
		summary(tokenB, -1, 0).andExpect(status().isOk()).andExpect(jsonPath("$.counts.completed").value(0)); // o B não enxerga o atendimento do A
		mvc.perform(get("/api/provider/reports/lapsed").header(HttpHeaders.AUTHORIZATION, tokenAttendant)).andExpect(status().isForbidden());
	}

	@Test
	void clientesQueSumiramSaoOsAtendidosHaMaisDeNDiasSemHorarioMarcadoESoTutorAtivo() throws Exception {
		appt(providerA, banho, joana, -70, LocalTime.of(10, 0), "COMPLETED", 5000, null, null); // sumiu
		appt(providerA, banho, pedro, -10, LocalTime.of(10, 0), "COMPLETED", 5000, null, null); // veio há pouco
		appt(providerA, banho, ana, -100, LocalTime.of(10, 0), "COMPLETED", 5000, null, null); // sumiu...
		appt(providerA, banho, ana, 5, LocalTime.of(10, 0), "SCHEDULED", 5000, null, null); // ...mas tem horário marcado: não sumiu
		appt(providerA, banho, inativo, -90, LocalTime.of(10, 0), "COMPLETED", 5000, null, null); // conta inativa: fora
		appt(providerB, banhoB, pedro, -200, LocalTime.of(10, 0), "COMPLETED", 5000, null, null); // de outro estúdio: fora

		String body = mvc.perform(get("/api/provider/reports/lapsed").param("days", "60").header(HttpHeaders.AUTHORIZATION, tokenA)).andExpect(status().isOk()).andReturn().getResponse()
				.getContentAsString();
		assertEquals(List.of("Joana Lima"), JsonPath.read(body, "$[*].name"));
		assertEquals("5511987654321", JsonPath.read(body, "$[0].phone"));
		assertEquals(70, (int) JsonPath.read(body, "$[0].daysSince"));

		// com um limite menor, o Pedro (10 dias) ainda não conta, mas com 5... fora do mínimo de 14 dias
		mvc.perform(get("/api/provider/reports/lapsed").param("days", "5").header(HttpHeaders.AUTHORIZATION, tokenA)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.days").exists());
		mvc.perform(get("/api/provider/reports/lapsed").param("days", "99999").header(HttpHeaders.AUTHORIZATION, tokenA)).andExpect(status().isBadRequest());
		String wider = mvc.perform(get("/api/provider/reports/lapsed").param("days", "14").header(HttpHeaders.AUTHORIZATION, tokenA)).andReturn().getResponse().getContentAsString();
		assertEquals(List.of("Joana Lima"), JsonPath.read(wider, "$[*].name"), "Pedro veio há 10 dias: ainda dentro do prazo mesmo com 14");
		assertFalse(wider.contains("Inativo"));
	}
}
