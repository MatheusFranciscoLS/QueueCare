package com.queuecare.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/**
 * API de agendar e cancelar (R2, R3, R4) ponta a ponta: HTTP real (MockMvc + filtros do Spring Security), serviços reais e
 * PostgreSQL real. O prestador de teste exige 60 min de antecedência e permite cancelar até 120 min antes.
 */
class AppointmentApiIntegrationTest extends AuthIntegrationTestBase {

	UUID customer1;
	UUID customer2;
	UUID providerUser;
	UUID providerId;
	UUID serviceId;
	UUID otherProviderId;

	@BeforeEach
	void seedScenario() {
		customer1 = seedUser("c1@exemplo.test", "CUSTOMER", true);
		customer2 = seedUser("c2@exemplo.test", "CUSTOMER", true);
		seedUser("atendente@exemplo.test", "ATTENDANT", true);
		providerUser = seedUser("prestador@exemplo.test", "PROVIDER", true);
		providerId = provider(providerUser);
		serviceId = service(providerId);
		window(providerId);
		otherProviderId = provider(seedUser("outro-prestador@exemplo.test", "PROVIDER", true));
	}

	private UUID provider(UUID userId) {
		return jdbc.queryForObject(
				"INSERT INTO providers (user_id, business_name, time_zone, minimum_notice_minutes, cancellation_deadline_minutes) "
						+ "VALUES (?, 'Negócio de Teste', 'America/Sao_Paulo', 60, 120) RETURNING id", UUID.class, userId);
	}

	private UUID service(UUID provider) {
		return jdbc.queryForObject("INSERT INTO services (provider_id, name, duration_minutes) VALUES (?, 'Serviço', 60) RETURNING id", UUID.class, provider);
	}

	private void window(UUID provider) {
		for (int day = 1; day <= 7; day++) {
			jdbc.update("INSERT INTO availability_windows (provider_id, day_of_week, start_time, end_time) VALUES (?, ?, '08:00', '12:00'), (?, ?, '14:00', '18:00')",
					provider, day, provider, day);
		}
	}

	private String tokenOf(String email) throws Exception {
		String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(json("email", email, "password", PASSWORD)))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		return "Bearer " + JsonPath.read(body, "$.accessToken");
	}

	/** Início local (horário do prestador) daqui a {@code dayOffset} dias, na hora cheia informada. */
	private static String slot(int dayOffset, int hour) {
		return LocalDate.now(ZoneId.of("America/Sao_Paulo")).plusDays(dayOffset) + "T" + String.format("%02d", hour) + ":00:00";
	}

	private ResultActions book(String bearer, String localStart) throws Exception {
		return book(bearer, providerId, localStart);
	}

	private ResultActions book(String bearer, UUID provider, String localStart) throws Exception {
		return mvc.perform(post("/api/appointments").header(HttpHeaders.AUTHORIZATION, bearer).contentType(MediaType.APPLICATION_JSON)
				.content("{\"providerId\":\"" + provider + "\",\"serviceId\":\"" + serviceId + "\",\"localStart\":\"" + localStart + "\"}"));
	}

	private ResultActions cancel(String bearer, String appointmentId) throws Exception {
		return mvc.perform(post("/api/appointments/" + appointmentId + "/cancel").header(HttpHeaders.AUTHORIZATION, bearer));
	}

	private String bookAndGetId(String bearer, String localStart) throws Exception {
		String body = book(bearer, localStart).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		return JsonPath.read(body, "$.id");
	}

	// --- agendar ---

	@Test
	void clienteAgendaEOClienteVemDoTokenNuncaDoCorpo() throws Exception {
		String c1 = tokenOf("c1@exemplo.test");

		// O corpo tenta se passar pelo cliente 2; o campo extra é ignorado.
		mvc.perform(post("/api/appointments").header(HttpHeaders.AUTHORIZATION, c1).contentType(MediaType.APPLICATION_JSON)
				.content("{\"providerId\":\"" + providerId + "\",\"serviceId\":\"" + serviceId + "\",\"localStart\":\"" + slot(7, 10)
						+ "\",\"customerId\":\"" + customer2 + "\"}"))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.status").value("SCHEDULED"))
				.andExpect(jsonPath("$.customerId").doesNotExist());

		assertEquals(customer1, jdbc.queryForObject("SELECT customer_id FROM appointments", UUID.class));
		assertEquals(1L, jdbc.queryForObject("SELECT count(*) FROM appointment_events WHERE actor_id = ?", Long.class, customer1));
	}

	@Test
	void horarioLocalViraUtcNaRespostaEFimUsaADuracaoDoServico() throws Exception {
		String c1 = tokenOf("c1@exemplo.test");

		String body = book(c1, slot(7, 10)).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();

		java.time.Instant start = java.time.Instant.parse(JsonPath.read(body, "$.startsAt"));
		java.time.Instant end = java.time.Instant.parse(JsonPath.read(body, "$.endsAt"));
		assertEquals(java.time.Duration.ofHours(1), java.time.Duration.between(start, end));
		assertEquals(13, start.atZone(ZoneId.of("UTC")).getHour(), "10:00 em São Paulo (UTC-3) = 13:00 UTC");
	}

	@Test
	void semTokenOuComPerfilErradoNaoAgenda() throws Exception {
		mvc.perform(post("/api/appointments").contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isUnauthorized());
		book(tokenOf("prestador@exemplo.test"), slot(7, 10)).andExpect(status().isForbidden());
		book(tokenOf("atendente@exemplo.test"), slot(7, 10)).andExpect(status().isForbidden());
		assertEquals(0L, jdbc.queryForObject("SELECT count(*) FROM appointments", Long.class));
	}

	@Test
	void horarioOcupadoEConflito409() throws Exception {
		bookAndGetId(tokenOf("c1@exemplo.test"), slot(7, 10));

		book(tokenOf("c2@exemplo.test"), slot(7, 10)).andExpect(status().isConflict()).andExpect(jsonPath("$.reason").value("SLOT_TAKEN"));
		assertEquals(1L, jdbc.queryForObject("SELECT count(*) FROM appointments", Long.class));
	}

	@Test
	void regrasDeNegocioViolamCom422ENaoGravam() throws Exception {
		String c1 = tokenOf("c1@exemplo.test");

		book(c1, slot(7, 17).replace("T17:00", "T17:30")).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.reason").value("OUTSIDE_AVAILABILITY"));
		book(c1, slot(-2, 10)).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.reason").value("IN_THE_PAST"));
		assertEquals(0L, jdbc.queryForObject("SELECT count(*) FROM appointments", Long.class));
	}

	@Test
	void prestadorOuServicoInexistentesSao404() throws Exception {
		String c1 = tokenOf("c1@exemplo.test");

		book(c1, UUID.randomUUID(), slot(7, 10)).andExpect(status().isNotFound()).andExpect(jsonPath("$.reason").value("PROVIDER_NOT_FOUND"));
		// Serviço de outro prestador.
		book(c1, otherProviderId, slot(7, 10)).andExpect(status().isNotFound()).andExpect(jsonPath("$.reason").value("SERVICE_NOT_FOUND_OR_INACTIVE"));
	}

	@Test
	void corpoInvalidoEh400() throws Exception {
		String c1 = tokenOf("c1@exemplo.test");

		mvc.perform(post("/api/appointments").header(HttpHeaders.AUTHORIZATION, c1).contentType(MediaType.APPLICATION_JSON).content("{}"))
				.andExpect(status().isBadRequest());
		mvc.perform(post("/api/appointments").header(HttpHeaders.AUTHORIZATION, c1).contentType(MediaType.APPLICATION_JSON)
				.content("{\"providerId\":\"" + providerId + "\",\"serviceId\":\"" + serviceId + "\",\"localStart\":\"amanha de manha\"}"))
				.andExpect(status().isBadRequest());
	}

	@Test
	void meusAgendamentosListaSoOsProprios() throws Exception {
		String c1 = tokenOf("c1@exemplo.test");
		String c2 = tokenOf("c2@exemplo.test");
		bookAndGetId(c1, slot(7, 10));
		bookAndGetId(c1, slot(7, 15));
		bookAndGetId(c2, slot(7, 11));

		mvc.perform(get("/api/appointments/mine").header(HttpHeaders.AUTHORIZATION, c1)).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2));
		mvc.perform(get("/api/appointments/mine").header(HttpHeaders.AUTHORIZATION, c2)).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
		mvc.perform(get("/api/appointments/mine")).andExpect(status().isUnauthorized());
	}

	// --- cancelar (R3 e R4) ---

	@Test
	void clienteCancelaOPropioAgendamentoELiberaOHorario() throws Exception {
		String c1 = tokenOf("c1@exemplo.test");
		String id = bookAndGetId(c1, slot(7, 10));

		cancel(c1, id).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));

		assertEquals(1L, jdbc.queryForObject("SELECT count(*) FROM appointment_events WHERE event_type = 'CANCELLED' AND actor_id = ?", Long.class, customer1));
		bookAndGetId(tokenOf("c2@exemplo.test"), slot(7, 10)); // o horário voltou a ficar livre
	}

	@Test
	void clienteNaoCancelaAgendamentoDeOutroCliente() throws Exception {
		String id = bookAndGetId(tokenOf("c1@exemplo.test"), slot(7, 10));

		cancel(tokenOf("c2@exemplo.test"), id).andExpect(status().isNotFound());
		cancel(tokenOf("c2@exemplo.test"), UUID.randomUUID().toString()).andExpect(status().isNotFound());
		assertEquals("SCHEDULED", jdbc.queryForObject("SELECT status FROM appointments", String.class));
	}

	@Test
	void cancelarDuasVezesEConflito409() throws Exception {
		String c1 = tokenOf("c1@exemplo.test");
		String id = bookAndGetId(c1, slot(7, 10));
		cancel(c1, id).andExpect(status().isOk());

		cancel(c1, id).andExpect(status().isConflict()).andExpect(jsonPath("$.reason").value("ALREADY_CANCELLED"));
	}

	@Test
	void foraDoPrazoOClienteNaoCancelaMasOPrestadorDonoPode() throws Exception {
		// Começa daqui a 30 min: dentro do prazo de 120 min do prestador.
		UUID id = jdbc.queryForObject("INSERT INTO appointments (provider_id, service_id, customer_id, starts_at, ends_at, status) "
				+ "VALUES (?, ?, ?, now() + interval '30 minutes', now() + interval '90 minutes', 'SCHEDULED') RETURNING id",
				UUID.class, providerId, serviceId, customer1);

		cancel(tokenOf("c1@exemplo.test"), id.toString()).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.reason").value("TOO_LATE"));
		assertEquals("SCHEDULED", jdbc.queryForObject("SELECT status FROM appointments", String.class));

		cancel(tokenOf("prestador@exemplo.test"), id.toString()).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
	}

	@Test
	void prestadorDeOutroNegocioEAtendenteNaoCancelam() throws Exception {
		String id = bookAndGetId(tokenOf("c1@exemplo.test"), slot(7, 10));

		cancel(tokenOf("outro-prestador@exemplo.test"), id).andExpect(status().isNotFound());
		cancel(tokenOf("atendente@exemplo.test"), id).andExpect(status().isForbidden());
		cancel("Bearer invalido", id).andExpect(status().isUnauthorized());
		assertEquals("SCHEDULED", jdbc.queryForObject("SELECT status FROM appointments", String.class));
	}

	@Test
	void agendamentoJaConcluidoNaoSeCancela() throws Exception {
		String c1 = tokenOf("c1@exemplo.test");
		String id = bookAndGetId(c1, slot(7, 10));
		jdbc.update("UPDATE appointments SET status = 'COMPLETED' WHERE id = ?::uuid", id);

		cancel(c1, id).andExpect(status().isConflict()).andExpect(jsonPath("$.reason").value("NOT_CANCELLABLE"));
	}

	// --- concorrência pela API ---

	@Test
	void doisClientesReservandoOMesmoHorarioPelaApiUmVenceOOutroRecebe409() throws Exception {
		String c1 = tokenOf("c1@exemplo.test");
		String c2 = tokenOf("c2@exemplo.test");
		for (int round = 0; round < 5; round++) {
			String when = slot(7 + round, 10);
			ExecutorService pool = Executors.newFixedThreadPool(2);
			CyclicBarrier startTogether = new CyclicBarrier(2);
			try {
				List<Callable<Integer>> tasks = new ArrayList<>();
				for (String token : List.of(c1, c2)) {
					tasks.add(() -> {
						startTogether.await(10, TimeUnit.SECONDS);
						return book(token, when).andReturn().getResponse().getStatus();
					});
				}
				List<Integer> statuses = new ArrayList<>();
				for (Future<Integer> future : pool.invokeAll(tasks, 60, TimeUnit.SECONDS)) {
					statuses.add(future.get());
				}
				assertEquals(1, statuses.stream().filter(s -> s == 201).count(), "exatamente um agenda: " + statuses);
				assertTrue(statuses.stream().filter(s -> s != 201).allMatch(s -> s == 409), "o outro recebe 409 e nunca 500: " + statuses);
			} finally {
				pool.shutdownNow();
			}
		}
		assertEquals(5L, jdbc.queryForObject("SELECT count(*) FROM appointments WHERE status <> 'CANCELLED'", Long.class));
	}

	@Test
	void doisCancelamentosSimultaneosDoMesmoAgendamentoUmVenceOOutroRecebe409() throws Exception {
		String c1 = tokenOf("c1@exemplo.test");
		String provider = tokenOf("prestador@exemplo.test");
		String id = bookAndGetId(c1, slot(7, 10));
		ExecutorService pool = Executors.newFixedThreadPool(2);
		CyclicBarrier startTogether = new CyclicBarrier(2);
		try {
			List<Callable<Integer>> tasks = new ArrayList<>();
			for (String token : List.of(c1, provider)) {
				tasks.add(() -> {
					startTogether.await(10, TimeUnit.SECONDS);
					return cancel(token, id).andReturn().getResponse().getStatus();
				});
			}
			List<Integer> statuses = new ArrayList<>();
			for (Future<Integer> future : pool.invokeAll(tasks, 60, TimeUnit.SECONDS)) {
				statuses.add(future.get());
			}
			assertEquals(1, statuses.stream().filter(s -> s == 200).count(), "exatamente um cancela: " + statuses);
			assertTrue(statuses.stream().filter(s -> s != 200).allMatch(s -> s == 409), "o outro recebe 409 e nunca 500: " + statuses);
		} finally {
			pool.shutdownNow();
		}
		assertEquals(1L, jdbc.queryForObject("SELECT count(*) FROM appointment_events WHERE event_type = 'CANCELLED'", Long.class), "um único evento de cancelamento");
	}
}
