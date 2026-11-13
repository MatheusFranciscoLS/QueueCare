package com.queuecare.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/**
 * O prestador configura o próprio negócio, serviços e janelas; o cliente consulta horários livres e agenda
 * (HTTP real + PostgreSQL real). Cada prestador só enxerga e altera o que é dele.
 */
class ProviderSetupApiIntegrationTest extends AuthIntegrationTestBase {

	private static final ZoneId SP = ZoneId.of("America/Sao_Paulo");

	String provider1;
	String provider2;
	String customer;

	@BeforeEach
	void seedUsers() throws Exception {
		seedUser("p1@exemplo.test", "PROVIDER", true);
		seedUser("p2@exemplo.test", "PROVIDER", true);
		seedUser("cliente@exemplo.test", "CUSTOMER", true);
		seedUser("atendente@exemplo.test", "ATTENDANT", true);
		provider1 = tokenOf("p1@exemplo.test");
		provider2 = tokenOf("p2@exemplo.test");
		customer = tokenOf("cliente@exemplo.test");
	}

	private String tokenOf(String email) throws Exception {
		String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(json("email", email, "password", PASSWORD)))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		return "Bearer " + JsonPath.read(body, "$.accessToken");
	}

	private ResultActions send(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder builder, String bearer, String body) throws Exception {
		builder = builder.header(HttpHeaders.AUTHORIZATION, bearer);
		if (body != null) {
			builder = builder.contentType(MediaType.APPLICATION_JSON).content(body);
		}
		return mvc.perform(builder);
	}

	private static String business(String name, String zone, int notice, int deadline) {
		return "{\"businessName\":\"" + name + "\",\"timeZone\":\"" + zone + "\",\"minimumNoticeMinutes\":" + notice + ",\"cancellationDeadlineMinutes\":" + deadline + "}";
	}

	private static String window(String day, String start, String end) {
		return "{\"dayOfWeek\":\"" + day + "\",\"start\":\"" + start + "\",\"end\":\"" + end + "\"}";
	}

	private static final String ALL_DAYS_08_12_14_18 = allDays();

	private static String allDays() {
		StringBuilder sb = new StringBuilder("[");
		String[] days = {"MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY", "SUNDAY"};
		for (int i = 0; i < days.length; i++) {
			if (i > 0) {
				sb.append(',');
			}
			sb.append(window(days[i], "08:00", "12:00")).append(',').append(window(days[i], "14:00", "18:00"));
		}
		return sb.append(']').toString();
	}

	/** Configura prestador 1 por completo e devolve {providerId, serviceId}. */
	private UUID[] setUpProvider1(int notice, int deadline, int duration) throws Exception {
		String providerBody = send(post("/api/provider"), provider1, business("Oficina Teste", "America/Sao_Paulo", notice, deadline))
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		String serviceBody = send(post("/api/provider/services"), provider1, "{\"name\":\"Troca de óleo\",\"durationMinutes\":" + duration + "}")
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		send(put("/api/provider/availability"), provider1, ALL_DAYS_08_12_14_18).andExpect(status().isOk());
		return new UUID[] {UUID.fromString(JsonPath.read(providerBody, "$.id")), UUID.fromString(JsonPath.read(serviceBody, "$.id"))};
	}

	private static String day(int offset) {
		return LocalDate.now(SP).plusDays(offset).toString();
	}

	private List<String> slotStarts(UUID providerId, UUID serviceId, String date) throws Exception {
		String body = mvc.perform(get("/api/providers/" + providerId + "/slots").param("serviceId", serviceId.toString()).param("date", date)
				.header(HttpHeaders.AUTHORIZATION, customer)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		return JsonPath.read(body, "$[*].localStart");
	}

	// --- negócio ---

	@Test
	void prestadorCadastraConsultaEAtualizaOProprioNegocio() throws Exception {
		send(get("/api/provider"), provider1, null).andExpect(status().isNotFound()).andExpect(jsonPath("$.reason").value("PROVIDER_NOT_CONFIGURED"));

		send(post("/api/provider"), provider1, business("Oficina do Zé", "America/Sao_Paulo", 60, 120)).andExpect(status().isCreated())
				.andExpect(jsonPath("$.businessName").value("Oficina do Zé")).andExpect(jsonPath("$.minimumNoticeMinutes").value(60));
		send(post("/api/provider"), provider1, business("De novo", "America/Sao_Paulo", 0, 0)).andExpect(status().isConflict())
				.andExpect(jsonPath("$.reason").value("PROVIDER_ALREADY_CONFIGURED"));
		send(put("/api/provider"), provider1, business("Oficina do Zé 2", "America/New_York", 30, 15)).andExpect(status().isOk())
				.andExpect(jsonPath("$.timeZone").value("America/New_York"));
		send(get("/api/provider"), provider1, null).andExpect(status().isOk()).andExpect(jsonPath("$.businessName").value("Oficina do Zé 2"));
		assertEquals(1L, count("SELECT count(*) FROM providers"));
	}

	@Test
	void configuracaoInvalidaEh400() throws Exception {
		send(post("/api/provider"), provider1, business("X", "Mundo/Imaginario", 0, 0)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.timeZone").exists());
		send(post("/api/provider"), provider1, business("  ", "America/Sao_Paulo", 0, 0)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.businessName").exists());
		send(post("/api/provider"), provider1, business("X", "America/Sao_Paulo", -1, 0)).andExpect(status().isBadRequest());
		send(post("/api/provider"), provider1, business("X", "America/Sao_Paulo", 0, 999999)).andExpect(status().isBadRequest());
		send(post("/api/provider"), provider1, "{}").andExpect(status().isBadRequest());
		assertEquals(0L, count("SELECT count(*) FROM providers"));
	}

	@Test
	void soPrestadorConfiguraONegocio() throws Exception {
		mvc.perform(get("/api/provider")).andExpect(status().isUnauthorized());
		send(get("/api/provider"), customer, null).andExpect(status().isForbidden());
		send(post("/api/provider"), customer, business("X", "America/Sao_Paulo", 0, 0)).andExpect(status().isForbidden());
		send(get("/api/provider"), tokenOf("atendente@exemplo.test"), null).andExpect(status().isForbidden());
		send(put("/api/provider/availability"), customer, "[]").andExpect(status().isForbidden());
	}

	// --- serviços ---

	@Test
	void prestadorGerenciaServicosESoOsProprios() throws Exception {
		send(post("/api/provider/services"), provider1, "{\"name\":\"X\",\"durationMinutes\":30}").andExpect(status().isNotFound()); // sem negócio
		send(post("/api/provider"), provider1, business("P1", "America/Sao_Paulo", 0, 0)).andExpect(status().isCreated());
		send(post("/api/provider"), provider2, business("P2", "America/Sao_Paulo", 0, 0)).andExpect(status().isCreated());

		String created = send(post("/api/provider/services"), provider1, "{\"name\":\"Alinhamento\",\"durationMinutes\":45}")
				.andExpect(status().isCreated()).andExpect(jsonPath("$.active").value(true)).andReturn().getResponse().getContentAsString();
		String id = JsonPath.read(created, "$.id");

		send(put("/api/provider/services/" + id), provider1, "{\"name\":\"Alinhamento 3D\",\"durationMinutes\":60,\"active\":false}")
				.andExpect(status().isOk()).andExpect(jsonPath("$.durationMinutes").value(60)).andExpect(jsonPath("$.active").value(false));
		send(get("/api/provider/services"), provider1, null).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
		send(get("/api/provider/services"), provider2, null).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
		// O prestador 2 não enxerga nem altera o serviço do prestador 1 (mesma resposta de "não existe").
		send(put("/api/provider/services/" + id), provider2, "{\"name\":\"Roubado\",\"durationMinutes\":10,\"active\":true}").andExpect(status().isNotFound());
		send(put("/api/provider/services/" + UUID.randomUUID()), provider1, "{\"name\":\"X\",\"durationMinutes\":10,\"active\":true}").andExpect(status().isNotFound());
		// Omitir active no PUT não reativa nada: é erro de validação.
		send(put("/api/provider/services/" + id), provider1, "{\"name\":\"Alinhamento 3D\",\"durationMinutes\":60}").andExpect(status().isBadRequest());
		assertEquals("Alinhamento 3D", jdbc.queryForObject("SELECT name FROM services WHERE id = ?::uuid", String.class, id));
	}

	@Test
	void servicoInvalidoEh400() throws Exception {
		send(post("/api/provider"), provider1, business("P1", "America/Sao_Paulo", 0, 0)).andExpect(status().isCreated());

		send(post("/api/provider/services"), provider1, "{\"name\":\"\",\"durationMinutes\":30}").andExpect(status().isBadRequest());
		send(post("/api/provider/services"), provider1, "{\"name\":\"X\",\"durationMinutes\":1}").andExpect(status().isBadRequest());
		send(post("/api/provider/services"), provider1, "{\"name\":\"X\",\"durationMinutes\":9999}").andExpect(status().isBadRequest());
		send(post("/api/provider/services"), provider1, "{\"name\":\"X\"}").andExpect(status().isBadRequest());
	}

	// --- janelas ---

	@Test
	void prestadorSubstituiAsJanelasEValidaSobreposicao() throws Exception {
		send(post("/api/provider"), provider1, business("P1", "America/Sao_Paulo", 0, 0)).andExpect(status().isCreated());

		send(put("/api/provider/availability"), provider1, "[" + window("MONDAY", "08:00", "12:00") + "," + window("MONDAY", "12:00", "18:00") + "]")
				.andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2)); // coladas são aceitas
		send(put("/api/provider/availability"), provider1, "[" + window("MONDAY", "08:00", "12:00") + "," + window("MONDAY", "11:00", "18:00") + "]")
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.windows").exists());
		send(put("/api/provider/availability"), provider1, "[" + window("MONDAY", "12:00", "08:00") + "]").andExpect(status().isBadRequest());
		send(get("/api/provider/availability"), provider1, null).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2)); // a tentativa inválida não mexeu
		send(put("/api/provider/availability"), provider1, "[]").andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
		assertEquals(0L, count("SELECT count(*) FROM availability_windows"));
	}

	@Test
	void cadaPrestadorSoVeAsPropriasJanelas() throws Exception {
		send(post("/api/provider"), provider1, business("P1", "America/Sao_Paulo", 0, 0)).andExpect(status().isCreated());
		send(post("/api/provider"), provider2, business("P2", "America/Sao_Paulo", 0, 0)).andExpect(status().isCreated());
		send(put("/api/provider/availability"), provider1, "[" + window("MONDAY", "08:00", "12:00") + "]").andExpect(status().isOk());

		send(get("/api/provider/availability"), provider2, null).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
		send(put("/api/provider/availability"), provider2, "[" + window("TUESDAY", "09:00", "10:00") + "]").andExpect(status().isOk());
		send(get("/api/provider/availability"), provider1, null).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].dayOfWeek").value("MONDAY"));
	}

	// --- horários livres ---

	@Test
	void horariosLivresSeguemAGradeDe15MinutosDentroDoExpediente() throws Exception {
		UUID[] ids = setUpProvider1(0, 0, 60);

		List<String> starts = slotStarts(ids[0], ids[1], day(7));

		assertEquals(26, starts.size(), "08:00 a 11:00 (13) + 14:00 a 17:00 (13), de 15 em 15 minutos");
		assertTrue(starts.get(0).endsWith("T08:00:00"));
		assertTrue(starts.get(12).endsWith("T11:00:00"));
		assertTrue(starts.get(13).endsWith("T14:00:00"));
		assertFalse(starts.stream().anyMatch(s -> s.endsWith("T11:15:00") || s.endsWith("T17:15:00")), "não cabe no expediente");
	}

	@Test
	void horarioReservadoSaiDaListaEVoltaQuandoCancelado() throws Exception {
		UUID[] ids = setUpProvider1(0, 0, 60);
		String date = day(7);
		String booking = "{\"providerId\":\"" + ids[0] + "\",\"serviceId\":\"" + ids[1] + "\",\"localStart\":\"" + date + "T10:00:00\"}";
		String created = send(post("/api/appointments"), customer, booking).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();

		List<String> after = slotStarts(ids[0], ids[1], date);
		assertEquals(19, after.size(), "removidos os 7 inícios que se sobrepõem a 10:00-11:00 (09:15 a 10:45)");
		assertFalse(after.contains(date + "T10:00:00"));
		assertTrue(after.contains(date + "T09:00:00"), "termina às 10:00: ainda cabe");
		assertTrue(after.contains(date + "T11:00:00"), "começa às 11:00: ainda cabe");

		send(post("/api/appointments/" + JsonPath.read(created, "$.id") + "/cancel"), customer, null).andExpect(status().isOk());
		assertEquals(26, slotStarts(ids[0], ids[1], date).size());
	}

	@Test
	void antecedenciaMinimaEPassadoRestringemOsHorarios() throws Exception {
		UUID[] ids = setUpProvider1(24 * 60, 0, 60); // exige 24 h de antecedência

		assertEquals(List.of(), slotStarts(ids[0], ids[1], day(-1)), "dia passado");
		assertEquals(List.of(), slotStarts(ids[0], ids[1], day(91)), "além de 90 dias");
		Instant limit = Instant.now().plusSeconds(24 * 3600);
		for (String date : List.of(day(0), day(1), day(2))) {
			String body = mvc.perform(get("/api/providers/" + ids[0] + "/slots").param("serviceId", ids[1].toString()).param("date", date)
					.header(HttpHeaders.AUTHORIZATION, customer)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
			List<String> starts = JsonPath.read(body, "$[*].startsAt");
			assertTrue(starts.stream().allMatch(s -> !Instant.parse(s).isBefore(limit)), "nenhum horário antes de agora + 24 h em " + date);
		}
		assertEquals(0, slotStarts(ids[0], ids[1], day(0)).size(), "hoje todos os horários ficam a menos de 24 h");
	}

	@Test
	void consultaDeHorariosExigeLoginEValidaPrestadorEServico() throws Exception {
		UUID[] ids = setUpProvider1(0, 0, 60);
		send(post("/api/provider"), provider2, business("P2", "America/Sao_Paulo", 0, 0)).andExpect(status().isCreated());
		UUID otherService = UUID.fromString(JsonPath.read(send(post("/api/provider/services"), provider2, "{\"name\":\"Outro\",\"durationMinutes\":30}")
				.andReturn().getResponse().getContentAsString(), "$.id"));

		mvc.perform(get("/api/providers/" + ids[0] + "/slots").param("serviceId", ids[1].toString()).param("date", day(7))).andExpect(status().isUnauthorized());
		mvc.perform(get("/api/providers/" + UUID.randomUUID() + "/slots").param("serviceId", ids[1].toString()).param("date", day(7))
				.header(HttpHeaders.AUTHORIZATION, customer)).andExpect(status().isNotFound());
		mvc.perform(get("/api/providers/" + ids[0] + "/slots").param("serviceId", otherService.toString()).param("date", day(7))
				.header(HttpHeaders.AUTHORIZATION, customer)).andExpect(status().isNotFound());
		mvc.perform(get("/api/providers/" + ids[0] + "/slots").param("serviceId", ids[1].toString()).param("date", "ontem")
				.header(HttpHeaders.AUTHORIZATION, customer)).andExpect(status().isBadRequest());
		// Serviço desativado some da consulta.
		send(put("/api/provider/services/" + ids[1]), provider1, "{\"name\":\"Troca de óleo\",\"durationMinutes\":60,\"active\":false}").andExpect(status().isOk());
		mvc.perform(get("/api/providers/" + ids[0] + "/slots").param("serviceId", ids[1].toString()).param("date", day(7))
				.header(HttpHeaders.AUTHORIZATION, customer)).andExpect(status().isNotFound());
	}

	@Test
	void todoHorarioListadoPodeSerAgendado() throws Exception {
		UUID[] ids = setUpProvider1(0, 0, 90);
		String date = day(5);

		List<String> starts = slotStarts(ids[0], ids[1], date);

		assertFalse(starts.isEmpty());
		for (String start : List.of(starts.get(0), starts.get(starts.size() - 1))) {
			send(post("/api/appointments"), customer,
					"{\"providerId\":\"" + ids[0] + "\",\"serviceId\":\"" + ids[1] + "\",\"localStart\":\"" + start + "\"}").andExpect(status().isCreated());
		}
	}

	@Test
	void janelasColadasPermitemServicoQueAtravessaOLimiteDaPrimeiraJanela() throws Exception {
		UUID[] ids = setUpProvider1(0, 0, 90);
		send(put("/api/provider/availability"), provider1, "[" + window("MONDAY", "08:00", "12:00") + "," + window("MONDAY", "12:00", "18:00") + "]").andExpect(status().isOk());
		String monday = LocalDate.now(SP).with(java.time.temporal.TemporalAdjusters.next(java.time.DayOfWeek.MONDAY)).toString();

		List<String> starts = slotStarts(ids[0], ids[1], monday);

		assertTrue(starts.contains(monday + "T11:00:00"), "11:00 a 12:30 cabe no bloco contínuo 08:00-18:00");
		assertTrue(starts.contains(monday + "T16:30:00"), "16:30 a 18:00 é o último");
		assertFalse(starts.contains(monday + "T16:45:00"));
		send(post("/api/appointments"), customer, "{\"providerId\":\"" + ids[0] + "\",\"serviceId\":\"" + ids[1] + "\",\"localStart\":\"" + monday + "T11:00:00\"}")
				.andExpect(status().isCreated());
	}

	@Test
	void janelaProximaDaMeiaNoiteNaoGeraHorarioQueAtravessaODia() throws Exception {
		UUID[] ids = setUpProvider1(0, 0, 60);
		send(put("/api/provider/availability"), provider1, "[" + window("TUESDAY", "22:00", "23:59") + "]").andExpect(status().isOk());
		String tuesday = LocalDate.now(SP).with(java.time.temporal.TemporalAdjusters.next(java.time.DayOfWeek.TUESDAY)).toString();

		List<String> starts = slotStarts(ids[0], ids[1], tuesday);

		assertEquals(List.of(tuesday + "T22:00:00", tuesday + "T22:15:00", tuesday + "T22:30:00", tuesday + "T22:45:00"), starts, "o último início possível é 22:45 (termina 23:45)");
	}
}
