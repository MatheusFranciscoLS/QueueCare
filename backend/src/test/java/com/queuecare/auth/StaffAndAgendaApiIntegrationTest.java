package com.queuecare.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * MATRIZ ADVERSÁRIA do vínculo atendente-prestador (ADR 0004): dois prestadores (A e B), cada um com um atendente vinculado
 * pela API, um atendente solto, dois clientes e um agendamento em cada prestador. Cada ator tenta ver e cancelar o que é de
 * cada prestador, com tokens reais, filtros reais e PostgreSQL real. Depois de TODAS as tentativas hostis, nada mudou.
 */
class StaffAndAgendaApiIntegrationTest extends AuthIntegrationTestBase {

	private static final ZoneId SP = ZoneId.of("America/Sao_Paulo");

	UUID userPA, userPB, userA1, userB1, userS, userC1, userC2;
	UUID providerA, providerB, serviceA, serviceB;
	String pa, pb, a1, b1, s, c1, c2;
	String apptA, apptB;

	@BeforeEach
	void seedMatrix() throws Exception {
		userPA = seedUser("pa@exemplo.test", "PROVIDER", true);
		userPB = seedUser("pb@exemplo.test", "PROVIDER", true);
		userA1 = seedUser("a1@exemplo.test", "ATTENDANT", true);
		userB1 = seedUser("b1@exemplo.test", "ATTENDANT", true);
		userS = seedUser("solto@exemplo.test", "ATTENDANT", true);
		userC1 = seedUser("c1@exemplo.test", "CUSTOMER", true);
		userC2 = seedUser("c2@exemplo.test", "CUSTOMER", true);
		providerA = business(userPA, "Negócio A", "America/Sao_Paulo");
		providerB = business(userPB, "Negócio B", "America/Sao_Paulo");
		serviceA = service(providerA);
		serviceB = service(providerB);
		pa = tokenOf("pa@exemplo.test");
		pb = tokenOf("pb@exemplo.test");
		a1 = tokenOf("a1@exemplo.test");
		b1 = tokenOf("b1@exemplo.test");
		s = tokenOf("solto@exemplo.test");
		c1 = tokenOf("c1@exemplo.test");
		c2 = tokenOf("c2@exemplo.test");
		// Os vínculos são feitos PELA API, pelos donos.
		send(post("/api/provider/staff"), pa, "{\"email\":\"a1@exemplo.test\"}").andExpect(status().isCreated());
		send(post("/api/provider/staff"), pb, "{\"email\":\"b1@exemplo.test\"}").andExpect(status().isCreated());
		apptA = book(c1, providerA, serviceA, slot(7, 10));
		apptB = book(c2, providerB, serviceB, slot(7, 10));
	}

	// ---------- helpers ----------

	private UUID business(UUID ownerUser, String name, String zone) {
		UUID id = jdbc.queryForObject("INSERT INTO providers (user_id, business_name, time_zone, minimum_notice_minutes, cancellation_deadline_minutes) "
				+ "VALUES (?, ?, ?, 0, 120) RETURNING id", UUID.class, ownerUser, name, zone);
		for (int day = 1; day <= 7; day++) {
			jdbc.update("INSERT INTO availability_windows (provider_id, day_of_week, start_time, end_time) VALUES (?, ?, '00:00', '23:59')", id, day);
		}
		return id;
	}

	private UUID service(UUID provider) {
		return jdbc.queryForObject("INSERT INTO services (provider_id, name, duration_minutes) VALUES (?, 'Serviço', 60) RETURNING id", UUID.class, provider);
	}

	private String tokenOf(String email) throws Exception {
		String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(json("email", email, "password", PASSWORD)))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		return "Bearer " + JsonPath.read(body, "$.accessToken");
	}

	private ResultActions send(MockHttpServletRequestBuilder builder, String bearer, String body) throws Exception {
		builder = builder.header(HttpHeaders.AUTHORIZATION, bearer);
		if (body != null) {
			builder = builder.contentType(MediaType.APPLICATION_JSON).content(body);
		}
		return mvc.perform(builder);
	}

	private static String slot(int dayOffset, int hour) {
		return LocalDate.now(SP).plusDays(dayOffset) + "T" + String.format("%02d", hour) + ":00:00";
	}

	private static String day(int offset) {
		return LocalDate.now(SP).plusDays(offset).toString();
	}

	private String book(String customerToken, UUID provider, UUID service, String localStart) throws Exception {
		String body = send(post("/api/appointments"), customerToken,
				"{\"providerId\":\"" + provider + "\",\"serviceId\":\"" + service + "\",\"localStart\":\"" + localStart + "\"}")
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		return JsonPath.read(body, "$.id");
	}

	private ResultActions agenda(String bearer, String date) throws Exception {
		return mvc.perform(get("/api/agenda").param("date", date).header(HttpHeaders.AUTHORIZATION, bearer));
	}

	private List<String> agendaIds(String bearer, String date) throws Exception {
		String body = agenda(bearer, date).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		return JsonPath.read(body, "$[*].id");
	}

	private ResultActions cancel(String bearer, String appointmentId) throws Exception {
		return mvc.perform(post("/api/appointments/" + appointmentId + "/cancel").header(HttpHeaders.AUTHORIZATION, bearer));
	}

	private String statusOf(String appointmentId) {
		return jdbc.queryForObject("SELECT status FROM appointments WHERE id = ?::uuid", String.class, appointmentId);
	}

	private List<Integer> race(List<Callable<Integer>> tasks) throws Exception {
		ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
		try {
			List<Integer> statuses = new ArrayList<>();
			for (Future<Integer> future : pool.invokeAll(tasks, 60, TimeUnit.SECONDS)) {
				assertTrue(!future.isCancelled(), "uma tentativa não terminou no prazo");
				statuses.add(future.get());
			}
			return statuses;
		} finally {
			pool.shutdownNow();
		}
	}

	// ---------- agenda: cada ator vê SÓ o prestador em que opera ----------

	@Test
	void matrizDaAgendaCadaAtorVeSoOSeuPrestador() throws Exception {
		String date = day(7);

		assertEquals(List.of(apptA), agendaIds(pa, date), "dono de A");
		assertEquals(List.of(apptA), agendaIds(a1, date), "atendente de A");
		assertEquals(List.of(apptB), agendaIds(pb, date), "dono de B");
		assertEquals(List.of(apptB), agendaIds(b1, date), "atendente de B");
		agenda(s, date).andExpect(status().isForbidden());
		agenda(c1, date).andExpect(status().isForbidden());
		agenda(c2, date).andExpect(status().isForbidden());
		mvc.perform(get("/api/agenda").param("date", date)).andExpect(status().isUnauthorized());
	}

	@Test
	void localDeTrabalhoDevolveNegocioEFusoSoDoPrestadorDeQuemPergunta() throws Exception {
		for (String token : List.of(pa, a1)) {
			mvc.perform(get("/api/agenda/workplace").param("providerId", providerB.toString()).header(HttpHeaders.AUTHORIZATION, token))
					.andExpect(status().isOk()).andExpect(jsonPath("$.businessName").value("Negócio A")).andExpect(jsonPath("$.timeZone").value("America/Sao_Paulo"));
		}
		for (String token : List.of(pb, b1)) {
			mvc.perform(get("/api/agenda/workplace").header(HttpHeaders.AUTHORIZATION, token)).andExpect(status().isOk()).andExpect(jsonPath("$.businessName").value("Negócio B"));
		}
		mvc.perform(get("/api/agenda/workplace").header(HttpHeaders.AUTHORIZATION, s)).andExpect(status().isForbidden()); // atendente sem vínculo
		mvc.perform(get("/api/agenda/workplace").header(HttpHeaders.AUTHORIZATION, c1)).andExpect(status().isForbidden());
		mvc.perform(get("/api/agenda/workplace").header(HttpHeaders.AUTHORIZATION, c2)).andExpect(status().isForbidden());
		mvc.perform(get("/api/agenda/workplace")).andExpect(status().isUnauthorized());
		seedUser("sem-negocio@exemplo.test", "PROVIDER", true);
		mvc.perform(get("/api/agenda/workplace").header(HttpHeaders.AUTHORIZATION, tokenOf("sem-negocio@exemplo.test"))).andExpect(status().isNotFound())
				.andExpect(jsonPath("$.reason").value("PROVIDER_NOT_CONFIGURED"));
	}

	@Test
	void parametroProviderIdNaAgendaEIgnoradoNuncaTrocaOContexto() throws Exception {
		for (String token : List.of(pa, a1)) {
			String body = mvc.perform(get("/api/agenda").param("date", day(7)).param("providerId", providerB.toString())
					.param("provider", providerB.toString()).header(HttpHeaders.AUTHORIZATION, token)).andExpect(status().isOk())
					.andReturn().getResponse().getContentAsString();
			assertEquals(List.of(apptA), JsonPath.<List<String>>read(body, "$[*].id"), "mesmo pedindo o B, vê só o A");
		}
	}

	@Test
	void agendaMostraNomeEIdDoClienteSemOutrosDadosPessoais() throws Exception {
		agenda(a1, day(7)).andExpect(status().isOk())
				.andExpect(jsonPath("$[0].customerId").value(userC1.toString()))
				.andExpect(jsonPath("$[0].customerName").value("Fulano de Teste"))
				.andExpect(jsonPath("$[0].serviceName").value("Serviço"))
				.andExpect(jsonPath("$[0].customerEmail").doesNotExist())
				.andExpect(jsonPath("$[0].email").doesNotExist())
				.andExpect(jsonPath("$[0].passwordHash").doesNotExist());
	}

	@Test
	void agendaNaoTrazCanceladosNemOutrosDiasEFicaOrdenada() throws Exception {
		String second = book(c2, providerA, serviceA, slot(7, 9));
		String third = book(c1, providerA, serviceA, slot(7, 15));
		book(c1, providerA, serviceA, slot(8, 10)); // outro dia
		String cancelled = book(c2, providerA, serviceA, slot(7, 12));
		cancel(pa, cancelled).andExpect(status().isOk());

		assertEquals(List.of(second, apptA, third), agendaIds(a1, day(7)), "por horário; sem o cancelado e sem o dia seguinte");
		assertEquals(List.of(), agendaIds(a1, day(30)), "dia sem agendamentos");
	}

	@Test
	void agendaDoPrestadorSemNegocioEAtendenteSemVinculo() throws Exception {
		seedUser("sem-negocio@exemplo.test", "PROVIDER", true);

		agenda(tokenOf("sem-negocio@exemplo.test"), day(7)).andExpect(status().isNotFound()).andExpect(jsonPath("$.reason").value("PROVIDER_NOT_CONFIGURED"));
		agenda(s, day(7)).andExpect(status().isForbidden());
	}

	@Test
	void agendaMantemONomeHistoricoDeClienteDesativado() throws Exception {
		jdbc.update("UPDATE users SET active = false WHERE id = ?", userC1);

		agenda(a1, day(7)).andExpect(status().isOk()).andExpect(jsonPath("$[0].customerName").value("Fulano de Teste")).andExpect(jsonPath("$[0].customerEmail").doesNotExist());
	}

	// ---------- cancelamento: o isolamento entre prestadores ----------

	@Test
	void matrizDoCancelamentoNinguemCancelaOQueNaoE_Seu() throws Exception {
		// Tentativas HOSTIS: sempre sobre o agendamento do OUTRO prestador/cliente.
		cancel(pa, apptB).andExpect(status().isNotFound());
		cancel(a1, apptB).andExpect(status().isNotFound());
		cancel(pb, apptA).andExpect(status().isNotFound());
		cancel(b1, apptA).andExpect(status().isNotFound());
		cancel(c1, apptB).andExpect(status().isNotFound());
		cancel(c2, apptA).andExpect(status().isNotFound());
		cancel(s, apptA).andExpect(status().isForbidden());
		cancel(s, apptB).andExpect(status().isForbidden());
		cancel(a1, UUID.randomUUID().toString()).andExpect(status().isNotFound());
		cancel("Bearer invalido", apptA).andExpect(status().isUnauthorized());

		assertEquals("SCHEDULED", statusOf(apptA), "nada mudou depois de todas as tentativas");
		assertEquals("SCHEDULED", statusOf(apptB));
		assertEquals(0L, count("SELECT count(*) FROM appointment_events WHERE event_type = 'CANCELLED'"));
	}

	@Test
	void donoEAtendenteCancelamOPrestadorDeles() throws Exception {
		String second = book(c2, providerA, serviceA, slot(7, 15));

		cancel(a1, apptA).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
		cancel(pa, second).andExpect(status().isOk());

		assertEquals(userA1, jdbc.queryForObject("SELECT actor_id FROM appointment_events WHERE appointment_id = ?::uuid AND event_type = 'CANCELLED'", UUID.class, apptA));
		assertEquals("SCHEDULED", statusOf(apptB), "o agendamento do B continua intocado");
	}

	@Test
	void equipeCancelaSemPrazoMasOClienteNao() throws Exception {
		// Agendamento do A que começa daqui a 30 min (dentro do prazo de 120 min), criado direto no banco.
		UUID near = jdbc.queryForObject("INSERT INTO appointments (provider_id, service_id, customer_id, starts_at, ends_at, status) "
				+ "VALUES (?, ?, ?, now() + interval '30 minutes', now() + interval '90 minutes', 'SCHEDULED') RETURNING id", UUID.class, providerA, serviceA, userC1);

		cancel(c1, near.toString()).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.reason").value("TOO_LATE"));
		cancel(a1, near.toString()).andExpect(status().isOk());
	}

	// ---------- vínculo: criar, listar, desvincular ----------

	@Test
	void donoVinculaListaEDesvinculaOProprioAtendente() throws Exception {
		send(get("/api/provider/staff"), pa, null).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].userId").value(userA1.toString()));
		// Vincula o atendente solto, com espaços e maiúsculas no e-mail; "role" e "providerId" extras no corpo são ignorados.
		send(post("/api/provider/staff"), pa, "{\"email\":\"  SOLTO@Exemplo.TEST \",\"role\":\"PROVIDER\",\"providerId\":\"" + providerB + "\"}")
				.andExpect(status().isCreated()).andExpect(jsonPath("$.userId").value(userS.toString())).andExpect(jsonPath("$.passwordHash").doesNotExist());
		assertEquals(providerA, jdbc.queryForObject("SELECT provider_id FROM provider_staff WHERE user_id = ?", UUID.class, userS), "o vínculo foi para o A, não para o B");
		assertEquals("ATTENDANT", jdbc.queryForObject("SELECT role FROM provider_staff WHERE user_id = ?", String.class, userS));
		send(get("/api/provider/staff"), pa, null).andExpect(jsonPath("$.length()").value(2));
		send(get("/api/provider/staff"), pb, null).andExpect(jsonPath("$.length()").value(1));

		send(delete("/api/provider/staff/" + userS), pa, null).andExpect(status().isNoContent());
		send(get("/api/provider/staff"), pa, null).andExpect(jsonPath("$.length()").value(1));
	}

	@Test
	void vinculoRepetidoEhConflitoSemDetalhes() throws Exception {
		String toSameProvider = send(post("/api/provider/staff"), pa, "{\"email\":\"a1@exemplo.test\"}").andExpect(status().isConflict())
				.andExpect(jsonPath("$.reason").value("STAFF_ALREADY_ASSIGNED")).andReturn().getResponse().getContentAsString();
		String toOtherProvider = send(post("/api/provider/staff"), pb, "{\"email\":\"a1@exemplo.test\"}").andExpect(status().isConflict())
				.andReturn().getResponse().getContentAsString();

		assertEquals(toSameProvider, toOtherProvider, "a resposta não revela se o vínculo é com este ou com outro prestador");
		assertTrue(!toOtherProvider.contains(providerA.toString()) && !toOtherProvider.contains("Negócio A"));
		assertEquals(providerA, jdbc.queryForObject("SELECT provider_id FROM provider_staff WHERE user_id = ?", UUID.class, userA1), "o vínculo original não foi roubado");
	}

	@Test
	void emailInexistenteNaoAtendenteOuInativoTemExatamenteAMesmaResposta() throws Exception {
		UUID inactive = seedUser("inativo@exemplo.test", "ATTENDANT", false);
		List<String> bodies = new ArrayList<>();
		for (String email : List.of("ninguem@exemplo.test", "c1@exemplo.test", "pb@exemplo.test", "inativo@exemplo.test")) {
			bodies.add(send(post("/api/provider/staff"), pa, "{\"email\":\"" + email + "\"}").andExpect(status().isNotFound())
					.andExpect(jsonPath("$.reason").value("STAFF_NOT_FOUND")).andReturn().getResponse().getContentAsString());
		}
		assertEquals(1, bodies.stream().distinct().count(), "inexistente, cliente, prestador e inativo respondem igual: " + bodies);
		assertEquals(0L, count("SELECT count(*) FROM provider_staff WHERE user_id = ?", inactive));
		send(post("/api/provider/staff"), pa, "{\"email\":\"\"}").andExpect(status().isBadRequest());
		send(post("/api/provider/staff"), pa, "{}").andExpect(status().isBadRequest());
	}

	@Test
	void soPrestadorGerenciaAEquipe() throws Exception {
		for (String token : List.of(a1, s, c1)) {
			send(get("/api/provider/staff"), token, null).andExpect(status().isForbidden());
			send(post("/api/provider/staff"), token, "{\"email\":\"solto@exemplo.test\"}").andExpect(status().isForbidden());
			send(delete("/api/provider/staff/" + userA1), token, null).andExpect(status().isForbidden());
		}
		mvc.perform(get("/api/provider/staff")).andExpect(status().isUnauthorized());
		assertEquals(providerA, jdbc.queryForObject("SELECT provider_id FROM provider_staff WHERE user_id = ?", UUID.class, userA1));
	}

	@Test
	void prestadorNaoDesvinculaAtendenteDeOutroPrestador() throws Exception {
		send(delete("/api/provider/staff/" + userA1), pb, null).andExpect(status().isNotFound());
		send(delete("/api/provider/staff/" + UUID.randomUUID()), pa, null).andExpect(status().isNotFound());
		send(delete("/api/provider/staff/" + userS), pa, null).andExpect(status().isNotFound()); // existe, mas não é do A

		assertEquals(providerA, jdbc.queryForObject("SELECT provider_id FROM provider_staff WHERE user_id = ?", UUID.class, userA1));
		assertEquals(2L, count("SELECT count(*) FROM provider_staff"));
	}

	@Test
	void prestadorSemNegocioNaoGerenciaEquipe() throws Exception {
		seedUser("sem-negocio@exemplo.test", "PROVIDER", true);
		String token = tokenOf("sem-negocio@exemplo.test");

		send(post("/api/provider/staff"), token, "{\"email\":\"solto@exemplo.test\"}").andExpect(status().isNotFound()).andExpect(jsonPath("$.reason").value("PROVIDER_NOT_CONFIGURED"));
		send(get("/api/provider/staff"), token, null).andExpect(status().isNotFound());
	}

	// ---------- o acesso segue o BANCO, não o token ----------

	@Test
	void desvincularTiraOAcessoNaHoraComOMesmoToken() throws Exception {
		agenda(a1, day(7)).andExpect(status().isOk());

		send(delete("/api/provider/staff/" + userA1), pa, null).andExpect(status().isNoContent());

		agenda(a1, day(7)).andExpect(status().isForbidden());
		cancel(a1, apptA).andExpect(status().isForbidden());
		assertEquals("SCHEDULED", statusOf(apptA));
	}

	@Test
	void trocarDePrestadorMudaOContextoSemNovoToken() throws Exception {
		send(delete("/api/provider/staff/" + userA1), pa, null).andExpect(status().isNoContent());
		send(post("/api/provider/staff"), pb, "{\"email\":\"a1@exemplo.test\"}").andExpect(status().isCreated());

		assertEquals(List.of(apptB), agendaIds(a1, day(7)), "o mesmo token agora vê o B");
		cancel(a1, apptA).andExpect(status().isNotFound());
	}

	@Test
	void atendenteDesativadoComTokenAindaValidoEhRecusado() throws Exception {
		agenda(a1, day(7)).andExpect(status().isOk());

		jdbc.update("UPDATE users SET active = false WHERE id = ?", userA1);

		agenda(a1, day(7)).andExpect(status().isUnauthorized());
		cancel(a1, apptA).andExpect(status().isUnauthorized());
	}

	@Test
	void bancoRecusaMudarOPerfilDeQuemEstaVinculado() {
		assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("UPDATE users SET role = 'PROVIDER' WHERE id = ?", userA1));
		assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("DELETE FROM users WHERE id = ?", userA1));
		assertEquals("ATTENDANT", jdbc.queryForObject("SELECT role FROM users WHERE id = ?", String.class, userA1));
	}

	// ---------- concorrência ----------

	@Test
	void doisPrestadoresVinculandoOMesmoAtendenteAoMesmoTempoUmVence() throws Exception {
		for (int round = 0; round < 5; round++) {
			jdbc.update("DELETE FROM provider_staff WHERE user_id = ?", userS);
			CyclicBarrier startTogether = new CyclicBarrier(2);
			List<Integer> statuses = race(List.of(
					() -> {
						startTogether.await(10, TimeUnit.SECONDS);
						return send(post("/api/provider/staff"), pa, "{\"email\":\"solto@exemplo.test\"}").andReturn().getResponse().getStatus();
					},
					() -> {
						startTogether.await(10, TimeUnit.SECONDS);
						return send(post("/api/provider/staff"), pb, "{\"email\":\"solto@exemplo.test\"}").andReturn().getResponse().getStatus();
					}));

			assertEquals(1, statuses.stream().filter(x -> x == 201).count(), "exatamente um vincula: " + statuses);
			assertTrue(statuses.stream().filter(x -> x != 201).allMatch(x -> x == 409), "o outro recebe 409 e nunca 500: " + statuses);
			assertEquals(1L, count("SELECT count(*) FROM provider_staff WHERE user_id = ?", userS), "nunca dois vínculos");
		}
	}

	@Test
	void vincularEDesvincularAoMesmoTempoDeixaOEstadoConsistente() throws Exception {
		for (int round = 0; round < 5; round++) {
			jdbc.update("DELETE FROM provider_staff WHERE user_id = ?", userA1);
			jdbc.update("INSERT INTO provider_staff (provider_id, user_id) VALUES (?, ?)", providerA, userA1);
			CyclicBarrier startTogether = new CyclicBarrier(2);
			List<Integer> statuses = race(List.of(
					() -> {
						startTogether.await(10, TimeUnit.SECONDS);
						return send(delete("/api/provider/staff/" + userA1), pa, null).andReturn().getResponse().getStatus();
					},
					() -> {
						startTogether.await(10, TimeUnit.SECONDS);
						return send(post("/api/provider/staff"), pb, "{\"email\":\"a1@exemplo.test\"}").andReturn().getResponse().getStatus();
					}));

			assertEquals(204, statuses.get(0), "o dono sempre consegue desvincular o seu: " + statuses);
			assertTrue(statuses.get(1) == 201 || statuses.get(1) == 409, "o vínculo concorrente dá 201 (depois) ou 409 (antes), nunca 500: " + statuses);
			long rows = count("SELECT count(*) FROM provider_staff WHERE user_id = ?", userA1);
			assertEquals(statuses.get(1) == 201 ? 1L : 0L, rows, "estado final coerente com o resultado");
			if (rows == 1) {
				assertEquals(providerB, jdbc.queryForObject("SELECT provider_id FROM provider_staff WHERE user_id = ?", UUID.class, userA1));
			}
		}
	}

	@Test
	void dois_cancelamentosSimultaneosDeAtendenteEClienteUmSoVence() throws Exception {
		String second = book(c1, providerA, serviceA, slot(7, 15));
		CyclicBarrier startTogether = new CyclicBarrier(2);

		List<Integer> statuses = race(List.of(
				() -> {
					startTogether.await(10, TimeUnit.SECONDS);
					return cancel(a1, second).andReturn().getResponse().getStatus();
				},
				() -> {
					startTogether.await(10, TimeUnit.SECONDS);
					return cancel(c1, second).andReturn().getResponse().getStatus();
				}));

		assertEquals(1, statuses.stream().filter(x -> x == 200).count(), "exatamente um cancela: " + statuses);
		assertTrue(statuses.stream().filter(x -> x != 200).allMatch(x -> x == 409), "o outro recebe 409: " + statuses);
		assertEquals(1L, count("SELECT count(*) FROM appointment_events WHERE appointment_id = ?::uuid AND event_type = 'CANCELLED'", second));
	}

	// ---------- desvincular enquanto o atendente está em plena requisição ----------

	@Test
	void desvincularEAgendaSimultaneosDaoSempreUmResultadoValido() throws Exception {
		for (int round = 0; round < 6; round++) {
			jdbc.update("DELETE FROM provider_staff WHERE user_id = ?", userA1);
			jdbc.update("INSERT INTO provider_staff (provider_id, user_id) VALUES (?, ?)", providerA, userA1);
			CyclicBarrier startTogether = new CyclicBarrier(2);

			List<Integer> statuses = race(List.of(
					() -> {
						startTogether.await(10, TimeUnit.SECONDS);
						return send(delete("/api/provider/staff/" + userA1), pa, null).andReturn().getResponse().getStatus();
					},
					() -> {
						startTogether.await(10, TimeUnit.SECONDS);
						return agenda(a1, day(7)).andReturn().getResponse().getStatus();
					}));

			assertEquals(204, statuses.get(0), "o dono sempre consegue desvincular: " + statuses);
			// A agenda que já começou usa o vínculo que viu no início (200); a que começa depois é recusada (403). Nunca 500.
			assertTrue(statuses.get(1) == 200 || statuses.get(1) == 403, "agenda concorrente: 200 (viu o vínculo) ou 403 (já desvinculado): " + statuses);
			agenda(a1, day(7)).andExpect(status().isForbidden()); // na requisição seguinte a revogação vale sempre
		}
	}

	@Test
	void desvincularECancelarSimultaneosDeixamOEstadoCoerente() throws Exception {
		for (int round = 0; round < 6; round++) {
			jdbc.update("DELETE FROM provider_staff WHERE user_id = ?", userA1);
			jdbc.update("INSERT INTO provider_staff (provider_id, user_id) VALUES (?, ?)", providerA, userA1);
			String appointment = book(c1, providerA, serviceA, slot(10 + round, 10));
			CyclicBarrier startTogether = new CyclicBarrier(2);

			List<Integer> statuses = race(List.of(
					() -> {
						startTogether.await(10, TimeUnit.SECONDS);
						return send(delete("/api/provider/staff/" + userA1), pa, null).andReturn().getResponse().getStatus();
					},
					() -> {
						startTogether.await(10, TimeUnit.SECONDS);
						return cancel(a1, appointment).andReturn().getResponse().getStatus();
					}));

			assertEquals(204, statuses.get(0), "o dono sempre consegue desvincular: " + statuses);
			assertTrue(statuses.get(1) == 200 || statuses.get(1) == 403, "cancelamento concorrente: 200 (viu o vínculo) ou 403 (já desvinculado): " + statuses);
			assertEquals(statuses.get(1) == 200 ? "CANCELLED" : "SCHEDULED", statusOf(appointment), "estado final coerente com o resultado");
			assertEquals(statuses.get(1) == 200 ? 1L : 0L, count("SELECT count(*) FROM appointment_events WHERE appointment_id = ?::uuid AND event_type = 'CANCELLED'", appointment));
			cancel(a1, appointment).andExpect(status().isForbidden()); // depois, sem acesso, sempre
		}
	}

	// ---------- agenda em dias de horário de verão ----------

	private void insertAppointment(UUID provider, UUID service, String startsAtUtc) {
		jdbc.update("INSERT INTO appointments (provider_id, service_id, customer_id, starts_at, ends_at, status) VALUES (?, ?, ?, ?::timestamptz, ?::timestamptz + interval '1 minute', 'SCHEDULED')",
				provider, service, userC1, startsAtUtc, startsAtUtc);
	}

	@Test
	void agendaDoDiaUsaOIntervaloLocalDoFusoEmDiasDeHorarioDeVerao() throws Exception {
		UUID userNy = seedUser("ny@exemplo.test", "PROVIDER", true);
		UUID providerNy = business(userNy, "Negócio NY", "America/New_York");
		UUID serviceNy = service(providerNy);
		String ny = tokenOf("ny@exemplo.test");
		// 14/03/2027 (primavera, dia de 23 h): 00:00 EST = 05:00Z; 03:00 EDT = 07:00Z; o dia termina em 15/03 04:00Z.
		String[] spring = {"2027-03-14T04:59:00Z", "2027-03-14T05:00:00Z", "2027-03-14T06:30:00Z", "2027-03-14T07:30:00Z", "2027-03-15T03:59:00Z", "2027-03-15T04:00:00Z"};
		// 07/11/2027 (outono, dia de 25 h): 00:00 EDT = 04:00Z; a hora 01:xx acontece duas vezes; o dia termina em 08/11 05:00Z.
		String[] fall = {"2027-11-07T03:59:00Z", "2027-11-07T04:00:00Z", "2027-11-07T05:30:00Z", "2027-11-07T06:30:00Z", "2027-11-08T04:59:00Z", "2027-11-08T05:00:00Z"};
		for (String start : spring) {
			insertAppointment(providerNy, serviceNy, start);
		}
		for (String start : fall) {
			insertAppointment(providerNy, serviceNy, start);
		}

		String springBody = agenda(ny, "2027-03-14").andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		String fallBody = agenda(ny, "2027-11-07").andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

		assertEquals(List.of("2027-03-14T05:00:00Z", "2027-03-14T06:30:00Z", "2027-03-14T07:30:00Z", "2027-03-15T03:59:00Z"),
				JsonPath.<List<String>>read(springBody, "$[*].startsAt"), "dia de 23 h: sem perder nem duplicar");
		assertEquals(List.of("2027-11-07T04:00:00Z", "2027-11-07T05:30:00Z", "2027-11-07T06:30:00Z", "2027-11-08T04:59:00Z"),
				JsonPath.<List<String>>read(fallBody, "$[*].startsAt"), "dia de 25 h: as duas passagens de 01:30 aparecem");
	}
}
