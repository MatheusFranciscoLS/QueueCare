package com.queuecare.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Reserva IDEMPOTENTE (ADR 0035): repetir o MESMO pedido (resposta perdida, tempo esgotado com a API acordando, duplo envio) devolve a reserva que já existe, e não um conflito com
 * a própria reserva do tutor. Qualquer diferença (outro tutor, outro pet, outro horário) continua sendo conflito: a trava do banco não muda.
 */
class IdempotentBookingIntegrationTest extends AuthIntegrationTestBase {

	UUID provider, service, maria, joao, bidu, thor;
	String tokenMaria, tokenJoao;
	String start;

	@BeforeEach
	void seed() throws Exception {
		UUID owner = seedUser("idem-owner@exemplo.test", "PROVIDER", true);
		maria = seedUser("idem-maria@exemplo.test", "CUSTOMER", true);
		joao = seedUser("idem-joao@exemplo.test", "CUSTOMER", true);
		provider = jdbc.queryForObject("INSERT INTO providers (user_id, business_name, time_zone, minimum_notice_minutes, cancellation_deadline_minutes) "
				+ "VALUES (?, 'Estúdio Idem', 'America/Sao_Paulo', 0, 120) RETURNING id", UUID.class, owner);
		service = jdbc.queryForObject("INSERT INTO services (provider_id, name, duration_minutes) VALUES (?, 'Banho', 60) RETURNING id", UUID.class, provider);
		for (int day = 1; day <= 7; day++) {
			jdbc.update("INSERT INTO availability_windows (provider_id, day_of_week, start_time, end_time) VALUES (?, ?, '08:00', '18:00')", provider, day);
		}
		bidu = jdbc.queryForObject("INSERT INTO pets (customer_id, name, species) VALUES (?, 'Bidu', 'DOG') RETURNING id", UUID.class, maria);
		thor = jdbc.queryForObject("INSERT INTO pets (customer_id, name, species) VALUES (?, 'Thor', 'DOG') RETURNING id", UUID.class, maria);
		jdbc.queryForObject("INSERT INTO pets (customer_id, name, species) VALUES (?, 'Rex', 'DOG') RETURNING id", UUID.class, joao);
		tokenMaria = "Bearer " + validToken(maria);
		tokenJoao = "Bearer " + validToken(joao);
		start = LocalDate.now(ZoneId.of("America/Sao_Paulo")).plusDays(2) + "T10:00:00";
	}

	private ResultActions book(String bearer, UUID pet) throws Exception {
		return mvc.perform(post("/api/appointments").header(HttpHeaders.AUTHORIZATION, bearer).contentType(MediaType.APPLICATION_JSON)
				.content(json("providerId", provider.toString(), "serviceId", service.toString(), "localStart", start, "petId", pet.toString())));
	}

	private UUID idOf(ResultActions result) throws Exception {
		return UUID.fromString(JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$.id"));
	}

	@Test
	void repetirOMesmoPedidoDevolveAReservaQueJaExisteSemDuplicarNada() throws Exception {
		UUID first = idOf(book(tokenMaria, bidu).andExpect(status().isCreated()));
		UUID second = idOf(book(tokenMaria, bidu).andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("SCHEDULED")));

		assertEquals(first, second, "o mesmo pedido devolve a mesma reserva");
		assertEquals(1, count("SELECT count(*) FROM appointments"));
		assertEquals(1, count("SELECT count(*) FROM appointment_events WHERE event_type = 'SCHEDULED'"), "um único evento de agendamento");
	}

	@Test
	void qualquerDiferencaContinuaSendoConflito() throws Exception {
		book(tokenMaria, bidu).andExpect(status().isCreated());
		book(tokenJoao, petOfToken(tokenJoao) == null ? bidu : UUID.fromString(petOfToken(tokenJoao))).andExpect(status().isConflict()).andExpect(jsonPath("$.reason").value("SLOT_TAKEN")); // outro tutor
		book(tokenMaria, thor).andExpect(status().isConflict()); // o mesmo tutor com OUTRO pet no mesmo horário
		assertEquals(1, count("SELECT count(*) FROM appointments"));
	}

	@Test
	void reservaCanceladaNaoServeDeResposta() throws Exception {
		UUID first = idOf(book(tokenMaria, bidu).andExpect(status().isCreated()));
		jdbc.update("UPDATE appointments SET status = 'CANCELLED' WHERE id = ?", first);
		UUID again = idOf(book(tokenMaria, bidu).andExpect(status().isCreated()));
		assertEquals(false, first.equals(again), "depois de cancelar, o mesmo pedido é uma reserva NOVA");
		assertEquals(2, count("SELECT count(*) FROM appointments"));
	}

	@Test
	void doisEnviosAoMesmoTempoResultamNaMesmaReserva() throws Exception {
		ExecutorService pool = Executors.newFixedThreadPool(2);
		CountDownLatch go = new CountDownLatch(1);
		try {
			List<Future<UUID>> results = List.of(
					pool.submit(() -> {
						go.await();
						return idOf(book(tokenMaria, bidu).andExpect(status().isCreated()));
					}),
					pool.submit(() -> {
						go.await();
						return idOf(book(tokenMaria, bidu).andExpect(status().isCreated()));
					}));
			go.countDown();
			assertEquals(results.get(0).get(), results.get(1).get(), "os dois envios simultâneos terminam na mesma reserva");
		} finally {
			pool.shutdownNow();
		}
		assertEquals(1, count("SELECT count(*) FROM appointments"));
	}
}
