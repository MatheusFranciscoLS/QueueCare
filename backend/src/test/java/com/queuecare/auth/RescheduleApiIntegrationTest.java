package com.queuecare.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.queuecare.scheduling.persistence.Appointment;
import com.queuecare.scheduling.persistence.AppointmentRepository;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Remarcar (ADR 0017) pela API real com PostgreSQL real: quem pode, quando, as regras do horário novo (as da reserva), o histórico
 * consultável e a concorrência, com corridas DETERMINÍSTICAS (uma transação aberta segura a linha enquanto a chamada tenta passar).
 */
class RescheduleApiIntegrationTest extends AuthIntegrationTestBase {

	static final ZoneId ZONE = ZoneId.of("America/Sao_Paulo");

	@Autowired
	DataSource dataSource;

	@Autowired
	AppointmentRepository appointmentRepository;

	UUID tutorA, tutorB, ownerP, ownerQ, attendantUser, lonelyAttendant, providerP, providerQ, serviceP, serviceQ;
	String tokenA, tokenB, tokenP, tokenQ, tokenAttendant, tokenLonely, tokenAdmin;

	@BeforeEach
	void seed() throws Exception {
		tutorA = seedUser("tutor-a@exemplo.test", "CUSTOMER", true);
		tutorB = seedUser("tutor-b@exemplo.test", "CUSTOMER", true);
		ownerP = seedUser("prestador-p@exemplo.test", "PROVIDER", true);
		ownerQ = seedUser("prestador-q@exemplo.test", "PROVIDER", true);
		attendantUser = seedUser("atendente@exemplo.test", "ATTENDANT", true);
		lonelyAttendant = seedUser("sem-vinculo@exemplo.test", "ATTENDANT", true);
		seedUser("admin@exemplo.test", "ADMIN", true);
		providerP = provider(ownerP, "Estúdio P");
		providerQ = provider(ownerQ, "Estúdio Q");
		serviceP = service(providerP);
		serviceQ = service(providerQ);
		tokenA = tokenOf("tutor-a@exemplo.test");
		tokenB = tokenOf("tutor-b@exemplo.test");
		tokenP = tokenOf("prestador-p@exemplo.test");
		tokenQ = tokenOf("prestador-q@exemplo.test");
		tokenAttendant = tokenOf("atendente@exemplo.test");
		tokenLonely = tokenOf("sem-vinculo@exemplo.test");
		tokenAdmin = tokenOf("admin@exemplo.test");
		mvc.perform(post("/api/provider/staff").header(HttpHeaders.AUTHORIZATION, tokenP).contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"atendente@exemplo.test\"}")).andExpect(status().isCreated());
	}

	private UUID provider(UUID owner, String name) {
		UUID id = jdbc.queryForObject("INSERT INTO providers (user_id, business_name, time_zone, minimum_notice_minutes, cancellation_deadline_minutes) "
				+ "VALUES (?, ?, 'America/Sao_Paulo', 60, 120) RETURNING id", UUID.class, owner, name);
		for (int day = 1; day <= 7; day++) {
			jdbc.update("INSERT INTO availability_windows (provider_id, day_of_week, start_time, end_time) VALUES (?, ?, '08:00', '12:00'), (?, ?, '14:00', '18:00')", id, day, id, day);
		}
		return id;
	}

	private UUID service(UUID provider) {
		return jdbc.queryForObject("INSERT INTO services (provider_id, name, duration_minutes) VALUES (?, 'Banho e tosa', 60) RETURNING id", UUID.class, provider);
	}

	private String tokenOf(String email) throws Exception {
		String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(json("email", email, "password", PASSWORD)))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		return "Bearer " + JsonPath.read(body, "$.accessToken");
	}

	static LocalDate day(int offset) {
		return LocalDate.now(ZONE).plusDays(offset);
	}

	static String slot(int dayOffset, int hour) {
		return day(dayOffset) + "T" + String.format("%02d", hour) + ":00:00";
	}

	private ResultActions send(MockHttpServletRequestBuilder request, String bearer, String content) throws Exception {
		MockHttpServletRequestBuilder r = bearer == null ? request : request.header(HttpHeaders.AUTHORIZATION, bearer);
		if (content != null) {
			r = r.contentType(MediaType.APPLICATION_JSON).content(content);
		}
		return mvc.perform(r);
	}

	/** Reserva pela API (como o cliente do token) e devolve o id. */
	private String book(String bearer, String localStart) throws Exception {
		String body = send(post("/api/appointments"), bearer,
				"{\"providerId\":\"" + providerP + "\",\"serviceId\":\"" + serviceP + "\",\"localStart\":\"" + localStart + "\",\"petId\":\"" + petOfToken(bearer) + "\"}")
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		return JsonPath.read(body, "$.id");
	}

	private ResultActions reschedule(String bearer, String id, String localStart) throws Exception {
		return send(post("/api/appointments/" + id + "/reschedule"), bearer, "{\"localStart\":\"" + localStart + "\"}");
	}

	private ResultActions history(String bearer, String id) throws Exception {
		return send(get("/api/appointments/" + id + "/events"), bearer, null);
	}

	private Map<String, Object> row(String id) {
		return jdbc.queryForMap("SELECT status, version, starts_at, ends_at, pet_id::text AS pet_id, pet_name FROM appointments WHERE id = ?::uuid", id);
	}

	private long rescheduledEvents(String id) {
		return count("SELECT count(*) FROM appointment_events WHERE appointment_id = ?::uuid AND event_type = 'RESCHEDULED'", id);
	}

	private Instant at(String localStart) {
		return LocalDateTime.parse(localStart).atZone(ZONE).toInstant();
	}

	/** Agendamento gravado direto no banco (com pet de teste), para cenários de situação e de prazo. */
	private String insert(UUID customer, String startsAtSql, String status) {
		return jdbc.queryForObject("INSERT INTO appointments (provider_id, service_id, customer_id, starts_at, ends_at, status, pet_id, pet_name, pet_species) "
				+ "VALUES (?, ?, ?, " + startsAtSql + ", (" + startsAtSql + ") + interval '1 hour', ?, ?, 'Pet de Teste', 'DOG') RETURNING id::text", String.class,
				providerP, serviceP, customer, status, petOf(customer));
	}

	private String insertAt(UUID customer, String localStart, String status) {
		return jdbc.queryForObject("INSERT INTO appointments (provider_id, service_id, customer_id, starts_at, ends_at, status, pet_id, pet_name, pet_species) "
				+ "VALUES (?, ?, ?, ?, ?, ?, ?, 'Pet de Teste', 'DOG') RETURNING id::text", String.class, providerP, serviceP, customer,
				java.sql.Timestamp.from(at(localStart)), java.sql.Timestamp.from(at(localStart).plusSeconds(3600)), status, petOf(customer));
	}

	// --- o caminho feliz e o que muda ---

	@Test
	void clienteRemarcaOMesmoAgendamentoMantendoDuracaoPetEStatusEGravandoUmEvento() throws Exception {
		String id = book(tokenA, slot(7, 9));
		Map<String, Object> before = row(id);

		reschedule(tokenA, id, slot(9, 14)).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id)).andExpect(jsonPath("$.status").value("SCHEDULED"))
				.andExpect(jsonPath("$.pet.name").value("Pet de Teste"));

		Map<String, Object> after = row(id);
		assertEquals(at(slot(9, 14)), ((java.sql.Timestamp) after.get("starts_at")).toInstant());
		assertEquals(Duration.ofHours(1), Duration.between(((java.sql.Timestamp) after.get("starts_at")).toInstant(), ((java.sql.Timestamp) after.get("ends_at")).toInstant()));
		assertEquals(before.get("pet_id"), after.get("pet_id"), "o pet e a cópia não mudam");
		assertEquals(((Number) before.get("version")).longValue() + 1, ((Number) after.get("version")).longValue());
		assertEquals(1L, count("SELECT count(*) FROM appointments"), "o MESMO agendamento foi movido, não criou outro");
		assertEquals(1L, rescheduledEvents(id));
		Map<String, Object> event = jdbc.queryForMap("SELECT actor_id, previous_starts_at, new_starts_at FROM appointment_events WHERE appointment_id = ?::uuid AND event_type = 'RESCHEDULED'", id);
		assertEquals(tutorA, event.get("actor_id"));
		assertEquals(at(slot(7, 9)), ((java.sql.Timestamp) event.get("previous_starts_at")).toInstant());
		assertEquals(at(slot(9, 14)), ((java.sql.Timestamp) event.get("new_starts_at")).toInstant());
	}

	@Test
	void donoEAtendenteVinculadoRemarcamEOEventoGuardaQuemFez() throws Exception {
		String id = book(tokenA, slot(7, 9));
		reschedule(tokenP, id, slot(8, 9)).andExpect(status().isOk());
		reschedule(tokenAttendant, id, slot(8, 10)).andExpect(status().isOk());
		assertEquals(2L, rescheduledEvents(id));
		List<UUID> actors = jdbc.queryForList("SELECT actor_id FROM appointment_events WHERE appointment_id = ?::uuid AND event_type = 'RESCHEDULED' ORDER BY created_at, id", UUID.class, id);
		assertEquals(List.of(ownerP, attendantUser), actors);
	}

	@Test
	void deslocarParaUmHorarioQueSobrepoeOProprioEPermitidoMasMesmoHorarioNao() throws Exception {
		String id = book(tokenA, slot(7, 9));
		reschedule(tokenA, id, day(7) + "T09:30:00").andExpect(status().isOk()); // 9:30-10:30 sobrepõe o 9:00-10:00 atual: ele não conflita consigo mesmo
		reschedule(tokenA, id, day(7) + "T09:30:00").andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.reason").value("NO_CHANGE"));
		assertEquals(1L, rescheduledEvents(id), "recusa não deixa evento");
	}

	// --- limites: a diferença entre os papéis ---

	@Test
	void clienteTemTresRemarcacoesPorAgendamentoEAQuartaERecusada() throws Exception {
		String id = book(tokenA, slot(7, 9));
		for (int i = 1; i <= 3; i++) {
			reschedule(tokenA, id, slot(7 + i, 9)).andExpect(status().isOk());
		}
		reschedule(tokenA, id, slot(11, 9)).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.reason").value("RESCHEDULE_LIMIT"));
		assertEquals(3L, rescheduledEvents(id));
		assertEquals(at(slot(10, 9)), ((java.sql.Timestamp) row(id).get("starts_at")).toInstant(), "a quarta não moveu nada");
	}

	@Test
	void equipeRemarcaSemLimiteAQuintaADecimaEAVigesimaSaoAceitas() throws Exception {
		String id = book(tokenA, slot(7, 9));
		for (int i = 1; i <= 20; i++) {
			// quem remarca é a equipe (dono nas pares, atendente nas ímpares); cada remarcação vai para um dia novo
			reschedule(i % 2 == 0 ? tokenP : tokenAttendant, id, slot(7 + i, 9)).andExpect(status().isOk());
			if (i == 5 || i == 10 || i == 20) {
				assertEquals(i, rescheduledEvents(id), "cada remarcação gera exatamente um evento (" + i + ")");
			}
		}
		assertEquals(20L, rescheduledEvents(id));
	}

	@Test
	void prazoDoClienteValeParaOHorarioAtualEAEquipeNaoTemPrazo() throws Exception {
		String soon = insert(tutorA, "now() + interval '30 minutes'", "SCHEDULED"); // dentro dos 120 min de prazo do prestador
		reschedule(tokenA, soon, slot(8, 9)).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.reason").value("TOO_LATE"));
		assertEquals(0L, rescheduledEvents(soon));
		reschedule(tokenP, soon, slot(8, 9)).andExpect(status().isOk()); // a equipe remarca mesmo em cima da hora
		assertEquals(1L, rescheduledEvents(soon));
	}

	@Test
	void mesmoHorarioEhNoChangeMesmoQuandoOAgendamentoJaEstaPertoDemais() throws Exception {
		// o agendamento começa daqui a 30 min (dentro da antecedência mínima de 60): pedir o MESMO horário é "sem mudança", não "perto demais"
		String soon = insert(tutorA, "now() + interval '30 minutes'", "SCHEDULED");
		java.sql.Timestamp start = jdbc.queryForObject("SELECT starts_at FROM appointments WHERE id = ?::uuid", java.sql.Timestamp.class, soon);
		String sameLocal = start.toInstant().atZone(ZONE).toLocalDateTime().withNano(0).toString();
		String localStart = sameLocal.length() == 16 ? sameLocal + ":00" : sameLocal;
		// com os segundos exatos do horário gravado (now() tem fração), o horário LOCAL pedido só coincide se a fração for descartada: usamos o gravado
		jdbc.update("UPDATE appointments SET starts_at = date_trunc('second', starts_at), ends_at = date_trunc('second', starts_at) + interval '1 hour' WHERE id = ?::uuid", soon);
		reschedule(tokenP, soon, localStart).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.reason").value("NO_CHANGE"));
	}

	// --- situação do agendamento ---

	@Test
	void soSeRemarcaAgendamentoScheduled() throws Exception {
		String cancelled = insertAt(tutorA, slot(7, 9), "CANCELLED");
		String checkedIn = insertAt(tutorA, slot(7, 10), "CHECKED_IN");
		String completed = insertAt(tutorA, slot(7, 11), "COMPLETED");
		for (String bearer : List.of(tokenA, tokenP)) {
			reschedule(bearer, cancelled, slot(9, 9)).andExpect(status().isConflict()).andExpect(jsonPath("$.reason").value("ALREADY_CANCELLED"));
			reschedule(bearer, checkedIn, slot(9, 9)).andExpect(status().isConflict()).andExpect(jsonPath("$.reason").value("NOT_RESCHEDULABLE"));
			reschedule(bearer, completed, slot(9, 9)).andExpect(status().isConflict()).andExpect(jsonPath("$.reason").value("NOT_RESCHEDULABLE"));
		}
		assertEquals(0L, count("SELECT count(*) FROM appointment_events WHERE event_type = 'RESCHEDULED'"));
	}

	// --- regras do horário novo: as da reserva ---

	@Test
	void novoHorarioSegueAsRegrasDaReserva() throws Exception {
		String id = book(tokenA, slot(7, 9));
		reschedule(tokenA, id, slot(9, 7)).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.reason").value("OUTSIDE_AVAILABILITY")); // antes do expediente
		reschedule(tokenA, id, slot(9, 12)).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.reason").value("OUTSIDE_AVAILABILITY")); // intervalo do almoço
		reschedule(tokenA, id, slot(-1, 9)).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.reason").value("IN_THE_PAST"));
		String soon = LocalDateTime.now(ZONE).plusMinutes(30).withSecond(0).withNano(0).toString() + ":00";
		reschedule(tokenA, id, soon).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.reason").value("TOO_SOON")); // antecedência mínima de 60 min
		// a equipe também segue as regras do horário (só o prazo e o limite diferem)
		reschedule(tokenP, id, slot(9, 7)).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.reason").value("OUTSIDE_AVAILABILITY"));
		assertEquals(0L, rescheduledEvents(id));
		assertEquals(at(slot(7, 9)), ((java.sql.Timestamp) row(id).get("starts_at")).toInstant());
		send(post("/api/appointments/" + id + "/reschedule"), tokenA, "{\"localStart\":\"amanha de manha\"}").andExpect(status().isBadRequest());
		send(post("/api/appointments/" + id + "/reschedule"), tokenA, "{}").andExpect(status().isBadRequest());
	}

	@Test
	void horarioOcupadoPorOutroAgendamentoDaSlotTakenENadaMuda() throws Exception {
		String mine = book(tokenA, slot(7, 9));
		book(tokenB, slot(8, 10));
		reschedule(tokenA, mine, slot(8, 10)).andExpect(status().isConflict()).andExpect(jsonPath("$.reason").value("SLOT_TAKEN"));
		reschedule(tokenA, mine, day(8) + "T10:30:00").andExpect(status().isConflict()).andExpect(jsonPath("$.reason").value("SLOT_TAKEN")); // sobreposição parcial com o de B
		assertEquals(at(slot(7, 9)), ((java.sql.Timestamp) row(mine).get("starts_at")).toInstant());
		assertEquals(0L, rescheduledEvents(mine));
	}

	// --- autorização ---

	@Test
	void agendamentoAlheioInexistenteOuDeOutroPrestadorTemAMesma404() throws Exception {
		String id = book(tokenA, slot(7, 9));
		String ghost = UUID.randomUUID().toString();
		for (String target : List.of(id, ghost)) {
			reschedule(tokenB, target, slot(9, 9)).andExpect(status().isNotFound()).andExpect(jsonPath("$.reason").value("APPOINTMENT_NOT_FOUND"));
			reschedule(tokenQ, target, slot(9, 9)).andExpect(status().isNotFound()).andExpect(jsonPath("$.reason").value("APPOINTMENT_NOT_FOUND"));
		}
		reschedule(tokenLonely, id, slot(9, 9)).andExpect(status().isForbidden()); // atendente sem vínculo: 403 antes de qualquer consulta
		reschedule(tokenAdmin, id, slot(9, 9)).andExpect(status().isForbidden());
		reschedule(null, id, slot(9, 9)).andExpect(status().isUnauthorized());
		assertEquals(0L, rescheduledEvents(id));
	}

	@Test
	void petArquivadoENaoImpedeERemarcarAgendamentoAntigoSemPetFunciona() throws Exception {
		String id = book(tokenA, slot(7, 9));
		String pet = (String) row(id).get("pet_id");
		send(post("/api/customer/pets/" + pet + "/archive"), tokenA, null).andExpect(status().isOk());
		reschedule(tokenA, id, slot(9, 9)).andExpect(status().isOk()).andExpect(jsonPath("$.pet.name").value("Pet de Teste"));

		// agendamento ANTIGO (anterior à V10): sem pet e com a marca; só a migration a escreve, então o teste desliga o gatilho por um instante
		jdbc.execute("ALTER TABLE appointments DISABLE TRIGGER appointments_legacy_guard");
		String legacy;
		try {
			legacy = jdbc.queryForObject("INSERT INTO appointments (provider_id, service_id, customer_id, starts_at, ends_at, status, legacy_without_pet) VALUES (?, ?, ?, ?, ?, 'SCHEDULED', true) RETURNING id::text",
					String.class, providerP, serviceP, tutorA, java.sql.Timestamp.from(at(slot(12, 9))), java.sql.Timestamp.from(at(slot(12, 9)).plusSeconds(3600)));
		} finally {
			jdbc.execute("ALTER TABLE appointments ENABLE TRIGGER appointments_legacy_guard");
		}
		reschedule(tokenA, legacy, slot(13, 9)).andExpect(status().isOk()).andExpect(jsonPath("$.pet").doesNotExist());
	}

	// --- concorrência (determinística) ---

	@Test
	void horarioDisputadoPorReservaEmAndamentoTerminaEmSlotTakenDepoisQueAOutraConfirma() throws Exception {
		String id = book(tokenA, slot(7, 9));
		UUID petB = petOf(tutorB);
		try (Connection other = dataSource.getConnection()) {
			other.setAutoCommit(false);
			try (var st = other.prepareStatement("INSERT INTO appointments (provider_id, service_id, customer_id, starts_at, ends_at, status, pet_id, pet_name, pet_species) VALUES (?, ?, ?, ?, ?, 'SCHEDULED', ?, 'Mel', 'CAT')")) {
				st.setObject(1, providerP);
				st.setObject(2, serviceP);
				st.setObject(3, tutorB);
				st.setTimestamp(4, java.sql.Timestamp.from(at(slot(9, 9))));
				st.setTimestamp(5, java.sql.Timestamp.from(at(slot(9, 9)).plusSeconds(3600)));
				st.setObject(6, petB);
				st.executeUpdate(); // a reserva do outro ainda NÃO foi confirmada: a checagem prévia não a enxerga
			}
			ExecutorService pool = Executors.newSingleThreadExecutor();
			try {
				Future<MockHttpServletResponse> moving = pool.submit(() -> reschedule(tokenA, id, slot(9, 9)).andReturn().getResponse());
				assertThrows(java.util.concurrent.TimeoutException.class, () -> moving.get(700, TimeUnit.MILLISECONDS), "a remarcação espera a restrição de exclusão decidir");
				other.commit();
				MockHttpServletResponse response = moving.get(10, TimeUnit.SECONDS);
				assertEquals(409, response.getStatus());
				assertEquals("SLOT_TAKEN", JsonPath.read(response.getContentAsString(), "$.reason"));
			} finally {
				pool.shutdownNow();
			}
		}
		assertEquals(at(slot(7, 9)), ((java.sql.Timestamp) row(id).get("starts_at")).toInstant());
		assertEquals(0L, rescheduledEvents(id));
	}

	@Test
	void checkInQueVenceNaFrenteFazARemarcacaoFalharPorVersao() throws Exception {
		String id = insertAt(tutorA, slot(7, 9), "SCHEDULED");
		assertConcurrentUpdateWhileRowIsChanged(id, "UPDATE appointments SET status = 'CHECKED_IN', version = version + 1 WHERE id = ?::uuid", tokenP);
		assertEquals("CHECKED_IN", row(id).get("status"));
		assertEquals(at(slot(7, 9)), ((java.sql.Timestamp) row(id).get("starts_at")).toInstant(), "não moveu um agendamento que já chegou");
		assertEquals(0L, rescheduledEvents(id));
	}

	@Test
	void terceiraRemarcacaoEmAndamentoFazAQuartaFalharPorVersaoENuncaPassaDoLimite() throws Exception {
		String id = book(tokenA, slot(7, 9));
		reschedule(tokenA, id, slot(8, 9)).andExpect(status().isOk());
		reschedule(tokenA, id, slot(9, 9)).andExpect(status().isOk()); // 2 feitas: a contagem lida pela próxima chamada é 2
		// uma 3ª remarcação "em andamento" (linha segura, evento ainda não visível): a chamada lê count = 2 e passa na checagem do limite...
		assertConcurrentUpdateWhileRowIsChanged(id,
				"UPDATE appointments SET version = version + 1 WHERE id = ?::uuid", tokenA);
		// ...mas a versão a recusa (o limite lido não valia mais): nada foi movido e continua dentro do limite
		assertTrue(rescheduledEvents(id) <= 3);
		assertEquals(at(slot(9, 9)), ((java.sql.Timestamp) row(id).get("starts_at")).toInstant());
	}

	/**
	 * Uma transação aberta atualiza a linha do agendamento (sobe o version) e a remarcação tenta passar: ela LÊ a versão antiga, espera o
	 * bloqueio, e quando a outra confirma sua gravação falha por versão (409 CONCURRENT_UPDATE), sem mover nada nem gravar evento.
	 */
	private void assertConcurrentUpdateWhileRowIsChanged(String id, String holderSql, String bearer) throws Exception {
		try (Connection holder = dataSource.getConnection()) {
			holder.setAutoCommit(false);
			try (var st = holder.prepareStatement(holderSql)) {
				st.setString(1, id);
				st.executeUpdate();
			}
			long eventsBefore = rescheduledEvents(id);
			ExecutorService pool = Executors.newSingleThreadExecutor();
			try {
				Future<MockHttpServletResponse> moving = pool.submit(() -> reschedule(bearer, id, slot(20, 9)).andReturn().getResponse());
				assertThrows(java.util.concurrent.TimeoutException.class, () -> moving.get(700, TimeUnit.MILLISECONDS), "a remarcação espera a linha");
				holder.commit();
				MockHttpServletResponse response = moving.get(10, TimeUnit.SECONDS);
				assertEquals(409, response.getStatus(), response.getContentAsString());
				assertEquals("CONCURRENT_UPDATE", JsonPath.read(response.getContentAsString(), "$.reason"));
				assertEquals(eventsBefore, rescheduledEvents(id));
			} finally {
				pool.shutdownNow();
			}
		}
	}

	@Test
	void remarcacoesSimultaneasDoMesmoAgendamentoNuncaPassamDoLimiteDoCliente() throws Exception {
		String id = book(tokenA, slot(7, 9));
		reschedule(tokenA, id, slot(8, 9)).andExpect(status().isOk()); // 1 feita; restam 2
		List<Future<MockHttpServletResponse>> futures = new ArrayList<>();
		ExecutorService pool = Executors.newFixedThreadPool(6);
		try {
			CountDownLatch go = new CountDownLatch(1);
			for (int i = 0; i < 6; i++) {
				int target = 9 + i;
				futures.add(pool.submit(() -> {
					go.await();
					return reschedule(tokenA, id, slot(target, 9)).andReturn().getResponse();
				}));
			}
			go.countDown();
			for (Future<MockHttpServletResponse> f : futures) {
				MockHttpServletResponse response = f.get(30, TimeUnit.SECONDS);
				String reason = response.getStatus() == 200 ? "OK" : JsonPath.read(response.getContentAsString(), "$.reason");
				assertTrue(List.of("OK", "CONCURRENT_UPDATE", "RESCHEDULE_LIMIT", "RESOURCE_BUSY").contains(reason), "resposta inesperada: " + response.getStatus() + " " + reason);
			}
		} finally {
			pool.shutdownNow();
		}
		assertTrue(rescheduledEvents(id) <= 3, "o cliente nunca passa de 3 remarcações, nem sob corrida: " + rescheduledEvents(id));
		assertEquals(rescheduledEvents(id), count("SELECT count(*) FROM appointment_events WHERE appointment_id = ?::uuid AND event_type = 'RESCHEDULED'", id));
	}

	@Test
	void falhaAoGravarOEventoDesfazTambemOMovimento() throws Exception {
		String id = book(tokenA, slot(7, 9));
		jdbc.execute("CREATE FUNCTION test_fail_reschedule_event() RETURNS trigger AS $$ BEGIN IF NEW.event_type = 'RESCHEDULED' THEN RAISE EXCEPTION 'falha simulada'; END IF; RETURN NEW; END; $$ LANGUAGE plpgsql");
		jdbc.execute("CREATE TRIGGER test_fail_reschedule_event_trg BEFORE INSERT ON appointment_events FOR EACH ROW EXECUTE FUNCTION test_fail_reschedule_event()");
		try {
			assertThrows(Exception.class, () -> reschedule(tokenA, id, slot(9, 9)));
		} finally {
			jdbc.execute("DROP TRIGGER test_fail_reschedule_event_trg ON appointment_events");
			jdbc.execute("DROP FUNCTION test_fail_reschedule_event()");
		}
		assertEquals(at(slot(7, 9)), ((java.sql.Timestamp) row(id).get("starts_at")).toInstant(), "o horário voltou: transação única");
		assertEquals(0L, rescheduledEvents(id));
	}

	@Test
	void checkInObsoletoDepoisDeRemarcarEhRecusadoPorVersao() throws Exception {
		String id = book(tokenA, slot(7, 9));
		Appointment stale = appointmentRepository.findById(UUID.fromString(id)).orElseThrow(); // lido ANTES da remarcação
		reschedule(tokenA, id, slot(9, 9)).andExpect(status().isOk());
		stale.checkIn();
		assertThrows(OptimisticLockingFailureException.class, () -> appointmentRepository.saveAndFlush(stale));
		assertEquals("SCHEDULED", row(id).get("status"));
		assertEquals(at(slot(9, 9)), ((java.sql.Timestamp) row(id).get("starts_at")).toInstant(), "o horário novo não foi desfeito pela gravação obsoleta");
	}

	// --- histórico consultável ---

	@Test
	void historicoParaOClienteNaoTrazNomesDeFuncionariosEParaAEquipeTraz() throws Exception {
		String id = book(tokenA, slot(7, 9));
		reschedule(tokenP, id, slot(8, 9)).andExpect(status().isOk());
		reschedule(tokenA, id, slot(9, 9)).andExpect(status().isOk());
		jdbc.update("UPDATE users SET name = 'Beatriz da Equipe' WHERE id = ?", ownerP);

		String forCustomer = history(tokenA, id).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(3)).andExpect(jsonPath("$[0].type").value("SCHEDULED"))
				.andExpect(jsonPath("$[0].by").value("CUSTOMER")).andExpect(jsonPath("$[1].type").value("RESCHEDULED")).andExpect(jsonPath("$[1].by").value("STAFF"))
				.andExpect(jsonPath("$[2].by").value("CUSTOMER")).andReturn().getResponse().getContentAsString();
		assertFalse(forCustomer.contains("Beatriz"), "o cliente não vê o nome do funcionário: " + forCustomer);
		assertNull(JsonPath.read(forCustomer, "$[1].actorName"));
		assertEquals(at(slot(7, 9)).toString(), JsonPath.read(forCustomer, "$[1].previousStartsAt"));
		assertEquals(at(slot(8, 9)).toString(), JsonPath.read(forCustomer, "$[1].newStartsAt"));

		history(tokenP, id).andExpect(status().isOk()).andExpect(jsonPath("$[1].actorName").value("Beatriz da Equipe")).andExpect(jsonPath("$[0].actorName").value("Fulano de Teste"));
		history(tokenAttendant, id).andExpect(status().isOk()).andExpect(jsonPath("$[1].actorName").value("Beatriz da Equipe"));
	}

	@Test
	void historicoNegaAcessoComAMesma404ParaAlheioEInexistente() throws Exception {
		String id = book(tokenA, slot(7, 9));
		String ghost = UUID.randomUUID().toString();
		for (String target : List.of(id, ghost)) {
			history(tokenB, target).andExpect(status().isNotFound()).andExpect(jsonPath("$.reason").value("APPOINTMENT_NOT_FOUND"));
			history(tokenQ, target).andExpect(status().isNotFound()).andExpect(jsonPath("$.reason").value("APPOINTMENT_NOT_FOUND"));
		}
		history(tokenLonely, id).andExpect(status().isForbidden());
		history(tokenAdmin, id).andExpect(status().isForbidden());
		history(null, id).andExpect(status().isUnauthorized());
	}

	@Test
	void historicoTemOrdemDeterministicaMesmoComEventosNoMesmoInstante() throws Exception {
		String id = book(tokenA, slot(7, 9));
		String same = "2030-01-01T10:00:00Z";
		jdbc.update("DELETE FROM appointment_events");
		for (int i = 0; i < 5; i++) {
			jdbc.update("INSERT INTO appointment_events (appointment_id, actor_id, event_type, created_at, previous_starts_at, new_starts_at) VALUES (?::uuid, ?, 'RESCHEDULED', ?::timestamptz, ?::timestamptz, ?::timestamptz)",
					id, ownerP, same, "2030-02-0" + (i + 1) + "T10:00:00Z", "2030-03-0" + (i + 1) + "T10:00:00Z");
		}
		List<String> expected = jdbc.queryForList("SELECT id::text FROM appointment_events WHERE appointment_id = ?::uuid ORDER BY created_at, id", String.class, id);
		for (int round = 0; round < 3; round++) {
			String body = history(tokenP, id).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
			List<String> previous = JsonPath.read(body, "$[*].previousStartsAt");
			List<String> byId = new ArrayList<>();
			for (String id2 : expected) {
				byId.add(jdbc.queryForObject("SELECT to_char(previous_starts_at AT TIME ZONE 'UTC', 'YYYY-MM-DD\"T\"HH24:MI:SS\"Z\"') FROM appointment_events WHERE id = ?::uuid", String.class, id2));
			}
			assertEquals(byId, previous, "mesmo instante: desempate por id, sempre na mesma ordem");
		}
	}

	// --- horários livres que ignoram o próprio agendamento ---

	@Test
	void horariosLivresIgnoramOProprioAgendamentoSoParaQuemPodeEOutrosCasosSaoIgnoradosSemErro() throws Exception {
		String mine = book(tokenA, slot(7, 10));
		String date = day(7).toString();
		String plain = slots(tokenA, date, null);
		assertFalse(JsonPath.<List<String>>read(plain, "$[*].localStart").contains(slot(7, 10)), "o próprio horário conta como ocupado sem o parâmetro");

		String withParam = slots(tokenA, date, mine);
		List<String> starts = JsonPath.read(withParam, "$[*].localStart");
		assertTrue(starts.contains(slot(7, 10)), "ignorando o próprio agendamento o horário volta a ser livre");
		assertTrue(starts.contains(day(7) + "T09:30:00") && starts.contains(day(7) + "T10:30:00"), "e dá para deslocar para horários que o sobrepõem");
		// a equipe do prestador também pode
		assertTrue(JsonPath.<List<String>>read(slots(tokenP, date, mine), "$[*].localStart").contains(slot(7, 10)));
		// quem não pode (outro cliente, equipe de outro prestador, id inexistente): parâmetro IGNORADO, resposta igual à comum, sem erro e sem pista
		assertEquals(slots(tokenB, date, null), slots(tokenB, date, mine));
		assertEquals(slots(tokenQ, date, null).length() > 0, slots(tokenQ, date, mine).length() > 0);
		assertEquals(plain, slots(tokenA, date, UUID.randomUUID().toString()));
	}

	@Test
	void horariosLivresDaRemarcacaoUsamADuracaoDoProprioAgendamentoEIgnoramOParametroDeOutroServico() throws Exception {
		String mine = book(tokenA, slot(7, 9)); // 60 minutos
		jdbc.update("UPDATE services SET duration_minutes = 90 WHERE id = ?", serviceP); // o serviço foi editado depois da reserva
		String date = day(7).toString();

		assertEquals(Duration.ofMinutes(90), slotLength(slots(tokenA, date, null)), "sem o parâmetro: a duração atual do serviço");
		String withParam = slots(tokenA, date, mine);
		assertEquals(Duration.ofMinutes(60), slotLength(withParam), "ao remarcar: a duração do PRÓPRIO agendamento, a mesma que a remarcação valida");
		// e todo horário oferecido é aceito pela remarcação (nunca diverge)
		for (String offered : JsonPath.<List<String>>read(withParam, "$[*].localStart").subList(0, 3)) {
			if (!offered.equals(slot(7, 9))) {
				reschedule(tokenA, mine, offered).andExpect(status().isOk());
				break;
			}
		}

		// parâmetro de um agendamento de OUTRO serviço: ignorado (o horário dele continua ocupado e a duração é a do serviço consultado)
		UUID other = jdbc.queryForObject("INSERT INTO services (provider_id, name, duration_minutes) VALUES (?, 'Hidratação', 30) RETURNING id", UUID.class, providerP);
		String booked = book(tokenA, slot(8, 9));
		String otherSlots = send(get("/api/providers/" + providerP + "/slots").param("serviceId", other.toString()).param("date", day(8).toString()).param("excludeAppointmentId", booked),
				tokenA, null).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		assertFalse(JsonPath.<List<String>>read(otherSlots, "$[*].localStart").contains(slot(8, 9)), "agendamento de outro serviço não é ignorado");
		assertEquals(Duration.ofMinutes(30), slotLength(otherSlots));
	}

	private static Duration slotLength(String slotsJson) {
		List<String> starts = JsonPath.read(slotsJson, "$[*].startsAt");
		List<String> ends = JsonPath.read(slotsJson, "$[*].endsAt");
		return Duration.between(Instant.parse(starts.get(0)), Instant.parse(ends.get(0)));
	}

	private String slots(String bearer, String date, String exclude) throws Exception {
		MockHttpServletRequestBuilder req = get("/api/providers/" + providerP + "/slots").param("serviceId", serviceP.toString()).param("date", date);
		if (exclude != null) {
			req = req.param("excludeAppointmentId", exclude);
		}
		return send(req, bearer, null).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
	}
}
