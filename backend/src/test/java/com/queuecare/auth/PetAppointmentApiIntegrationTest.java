package com.queuecare.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.queuecare.scheduling.persistence.Appointment;
import com.queuecare.scheduling.persistence.AppointmentRepository;
import java.sql.Connection;
import java.time.LocalDate;
import java.time.ZoneId;
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
 * Reserva com pet, cópia (snapshot) e leitura pela agenda, fila e "Meus agendamentos" (ADR 0016, PR 47), pela API real com PostgreSQL
 * real. Cobre: dono e estado do pet na reserva, quem vê o quê (autorização na consulta, não na interface), a cópia que acompanha a
 * edição só enquanto SCHEDULED e as corridas reservar x arquivar e editar x check-in, de forma DETERMINÍSTICA (uma transação aberta
 * segura a linha enquanto a outra chamada tenta passar).
 */
class PetAppointmentApiIntegrationTest extends AuthIntegrationTestBase {

	static final ZoneId ZONE = ZoneId.of("America/Sao_Paulo");

	@Autowired
	DataSource dataSource;

	@Autowired
	AppointmentRepository appointmentRepository;

	UUID tutorA, tutorB, ownerP, ownerQ, providerP, providerQ, serviceP, serviceQ;
	String tokenA, tokenB, tokenP, tokenQ, tokenAttendant, tokenAdmin;

	@BeforeEach
	void seed() throws Exception {
		tutorA = seedUser("tutor-a@exemplo.test", "CUSTOMER", true);
		tutorB = seedUser("tutor-b@exemplo.test", "CUSTOMER", true);
		ownerP = seedUser("prestador-p@exemplo.test", "PROVIDER", true);
		ownerQ = seedUser("prestador-q@exemplo.test", "PROVIDER", true);
		seedUser("atendente@exemplo.test", "ATTENDANT", true);
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

	/** Dia local do prestador daqui a {@code dayOffset} dias. */
	static LocalDate day(int dayOffset) {
		return LocalDate.now(ZONE).plusDays(dayOffset);
	}

	static String slot(int dayOffset, int hour) {
		return day(dayOffset) + "T" + String.format("%02d", hour) + ":00:00";
	}

	private ResultActions send(MockHttpServletRequestBuilder request, String bearer, String content) throws Exception {
		MockHttpServletRequestBuilder r = request.header(HttpHeaders.AUTHORIZATION, bearer);
		if (content != null) {
			r = r.contentType(MediaType.APPLICATION_JSON).content(content);
		}
		return mvc.perform(r);
	}

	/** Cria um pet do tutor pela API e devolve o id. */
	private String pet(String bearer, String name, String species, String breed, String size, String notes) throws Exception {
		String body = send(post("/api/customer/pets"), bearer, PetApiIntegrationTest.body(name, species, breed, size, notes)).andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		return JsonPath.read(body, "$.id");
	}

	private String bookingBody(UUID provider, UUID service, String localStart, String petId) {
		return "{\"providerId\":\"" + provider + "\",\"serviceId\":\"" + service + "\",\"localStart\":\"" + localStart + "\""
				+ (petId == null ? "" : ",\"petId\":\"" + petId + "\"") + "}";
	}

	private ResultActions book(String bearer, String localStart, String petId) throws Exception {
		return send(post("/api/appointments"), bearer, bookingBody(providerP, serviceP, localStart, petId));
	}

	private String bookOk(String bearer, String localStart, String petId) throws Exception {
		String body = book(bearer, localStart, petId).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		return JsonPath.read(body, "$.id");
	}

	private ResultActions dayAgenda(String bearer, int dayOffset) throws Exception {
		return send(get("/api/agenda").param("date", day(dayOffset).toString()), bearer, null);
	}

	private ResultActions weekAgenda(String bearer, int dayOffset) throws Exception {
		return send(get("/api/agenda/week").param("start", day(dayOffset).toString()), bearer, null);
	}

	private Map<String, Object> row(String appointmentId) {
		return jdbc.queryForMap("SELECT status, version, pet_id::text AS pet_id, pet_name, pet_species, pet_breed, pet_size, pet_notes FROM appointments WHERE id = ?::uuid",
				appointmentId);
	}

	// --- reserva: dono e estado do pet ---

	@Test
	void reservaComPetGravaACopiaERespondeSoNomeEEspecie() throws Exception {
		String rex = pet(tokenA, "Rex", "DOG", "Poodle", "SMALL", "Tem coceira na pata.");
		String body = book(tokenA, slot(7, 9), rex).andExpect(status().isCreated()).andExpect(jsonPath("$.pet.name").value("Rex"))
				.andExpect(jsonPath("$.pet.species").value("DOG")).andReturn().getResponse().getContentAsString();
		Map<String, Object> petJson = JsonPath.read(body, "$.pet");
		assertEquals(2, petJson.size(), "o cliente recebe só nome e espécie do pet: " + petJson);
		assertFalse(body.contains("coceira"), body);

		Map<String, Object> row = row(JsonPath.read(body, "$.id"));
		assertEquals(rex, row.get("pet_id"));
		assertEquals("Rex", row.get("pet_name"));
		assertEquals("DOG", row.get("pet_species"));
		assertEquals("Poodle", row.get("pet_breed"));
		assertEquals("SMALL", row.get("pet_size"));
		assertEquals("Tem coceira na pata.", row.get("pet_notes"));
	}

	@Test
	void reservaSemPetContinuaFuncionandoAteOPetSerObrigatorio() throws Exception {
		String body = book(tokenA, slot(7, 9), null).andExpect(status().isCreated()).andExpect(jsonPath("$.pet").doesNotExist()).andReturn().getResponse().getContentAsString();
		assertNull(row(JsonPath.read(body, "$.id")).get("pet_id"));
		send(get("/api/appointments/mine"), tokenA, null).andExpect(status().isOk()).andExpect(jsonPath("$[0].pet").doesNotExist());
	}

	@Test
	void petDeOutroClienteEIndistinguivelDePetInexistenteMesmoArquivado() throws Exception {
		String petB = pet(tokenB, "Mel", "CAT", null, null, "segredo do tutor B");
		String archivedB = pet(tokenB, "Bob", "DOG", null, null, null);
		send(post("/api/customer/pets/" + archivedB + "/archive"), tokenB, null).andExpect(status().isOk());

		String ghost = UUID.randomUUID().toString();
		String reference = null;
		for (String target : List.of(petB, archivedB, ghost)) {
			String response = book(tokenA, slot(7, 9), target).andExpect(status().isNotFound()).andExpect(jsonPath("$.reason").value("PET_NOT_FOUND")).andReturn()
					.getResponse().getContentAsString();
			assertFalse(response.contains("segredo"), response);
			assertFalse(response.contains("Mel"), response);
			Object shape = JsonPath.read(response, "$.detail") + "|" + JsonPath.read(response, "$.status");
			if (reference == null) {
				reference = shape.toString();
			}
			assertEquals(reference, shape.toString(), "a resposta é a mesma para pet alheio, alheio arquivado e inexistente");
		}
		assertEquals(0L, count("SELECT count(*) FROM appointments"), "nada foi gravado");
	}

	@Test
	void petArquivadoDoProprioClienteRecebe422ESoVoltaDepoisDeReativar() throws Exception {
		String rex = pet(tokenA, "Rex", "DOG", null, null, null);
		send(post("/api/customer/pets/" + rex + "/archive"), tokenA, null).andExpect(status().isOk());
		book(tokenA, slot(7, 9), rex).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.reason").value("PET_INACTIVE"));
		assertEquals(0L, count("SELECT count(*) FROM appointments"));
		send(post("/api/customer/pets/" + rex + "/reactivate"), tokenA, null).andExpect(status().isOk());
		book(tokenA, slot(7, 9), rex).andExpect(status().isCreated());
	}

	@Test
	void petIdMalFormadoRecebe400() throws Exception {
		send(post("/api/appointments"), tokenA, "{\"providerId\":\"" + providerP + "\",\"serviceId\":\"" + serviceP + "\",\"localStart\":\"" + slot(7, 9) + "\",\"petId\":\"nao-e-uuid\"}")
				.andExpect(status().isBadRequest());
		assertEquals(0L, count("SELECT count(*) FROM appointments"));
	}

	@Test
	void arquivarPetComReservaFuturaNaoInvalidaAReservaMasBloqueiaNovas() throws Exception {
		String rex = pet(tokenA, "Rex", "DOG", "Poodle", "SMALL", "Nota");
		String id = bookOk(tokenA, slot(7, 9), rex);
		send(post("/api/customer/pets/" + rex + "/archive"), tokenA, null).andExpect(status().isOk());
		assertEquals("SCHEDULED", row(id).get("status"));
		dayAgenda(tokenP, 7).andExpect(status().isOk()).andExpect(jsonPath("$[0].pet.name").value("Rex")).andExpect(jsonPath("$[0].pet.notes").value("Nota"));
		book(tokenA, slot(7, 10), rex).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.reason").value("PET_INACTIVE"));
	}

	// --- leitura: quem vê o quê ---

	@Test
	void agendaDoDiaMostraOPetCompletoESemanaSoNomeEspecieEPorte() throws Exception {
		String rex = pet(tokenA, "Rex", "DOG", "Poodle", "MEDIUM", "Tem coceira na pata.");
		bookOk(tokenA, slot(7, 9), rex);
		bookOk(tokenA, slot(7, 10), null); // sem pet: o campo vem nulo

		dayAgenda(tokenP, 7).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2)).andExpect(jsonPath("$[0].pet.name").value("Rex"))
				.andExpect(jsonPath("$[0].pet.species").value("DOG")).andExpect(jsonPath("$[0].pet.breed").value("Poodle")).andExpect(jsonPath("$[0].pet.size").value("MEDIUM"))
				.andExpect(jsonPath("$[0].pet.notes").value("Tem coceira na pata.")).andExpect(jsonPath("$[1].pet").doesNotExist());
		// o atendente vinculado ao prestador também vê
		dayAgenda(tokenAttendant, 7).andExpect(status().isOk()).andExpect(jsonPath("$[0].pet.notes").value("Tem coceira na pata."));
		// a semana (várias linhas) não leva raça nem observações
		String week = weekAgenda(tokenP, 7).andExpect(status().isOk()).andExpect(jsonPath("$[0].pet.name").value("Rex")).andExpect(jsonPath("$[0].pet.species").value("DOG"))
				.andExpect(jsonPath("$[0].pet.size").value("MEDIUM")).andExpect(jsonPath("$[0].pet.breed").doesNotExist()).andExpect(jsonPath("$[0].pet.notes").doesNotExist())
				.andReturn().getResponse().getContentAsString();
		assertFalse(week.contains("coceira") || week.contains("Poodle"), week);
	}

	@Test
	void filaMostraOPetCompletoDeQuemJaChegou() throws Exception {
		UUID petId = UUID.fromString(pet(tokenA, "Rex", "DOG", "Poodle", "LARGE", "Morde o secador."));
		UUID appointment = insertToday(petId, "Rex", "DOG", "Poodle", "LARGE", "Morde o secador.", "CHECKED_IN");
		jdbc.update("INSERT INTO appointment_events (appointment_id, actor_id, event_type) VALUES (?, ?, 'CHECKED_IN')", appointment, ownerP);
		send(get("/api/agenda/queue"), tokenAttendant, null).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].pet.name").value("Rex"))
				.andExpect(jsonPath("$[0].pet.breed").value("Poodle")).andExpect(jsonPath("$[0].pet.notes").value("Morde o secador."));
		send(get("/api/agenda/queue"), tokenQ, null).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
	}

	@Test
	void ninguemMaisVeOPetNemSuasObservacoes() throws Exception {
		String rex = pet(tokenA, "Rex", "DOG", "Poodle", "MEDIUM", "observação-privada");
		bookOk(tokenA, slot(7, 9), rex);

		// outro prestador: nada do estúdio P
		for (ResultActions r : List.of(dayAgenda(tokenQ, 7), weekAgenda(tokenQ, 7))) {
			String response = r.andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0)).andReturn().getResponse().getContentAsString();
			assertFalse(response.contains("observação-privada") || response.contains("Rex"), response);
		}
		// o outro tutor não vê agendamento nem pet do tutor A
		send(get("/api/appointments/mine"), tokenB, null).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
		// o próprio tutor vê só nome e espécie na lista (as observações são dele, mas a lista não as repete)
		String mine = send(get("/api/appointments/mine"), tokenA, null).andExpect(status().isOk()).andExpect(jsonPath("$[0].pet.name").value("Rex"))
				.andReturn().getResponse().getContentAsString();
		Map<String, Object> petJson = JsonPath.read(mine, "$[0].pet");
		assertEquals(2, petJson.size(), petJson.toString());
		assertFalse(mine.contains("observação-privada") || mine.contains("Poodle"), mine);
		// o administrador não tem agenda nem pets
		dayAgenda(tokenAdmin, 7).andExpect(status().isForbidden());
		send(get("/api/customer/pets"), tokenAdmin, null).andExpect(status().isForbidden());
		// um prestador também não lista pets
		send(get("/api/customer/pets"), tokenP, null).andExpect(status().isForbidden());
	}

	@Test
	void agendaELeOPetDaCopiaNuncaDoCadastro() throws Exception {
		String rex = pet(tokenA, "Rex", "DOG", "Poodle", "MEDIUM", "Nota original");
		bookOk(tokenA, slot(7, 9), rex);
		// alteração direta no cadastro, sem passar pela API (nenhuma atualização da cópia): a leitura não pode enxergá-la
		jdbc.update("UPDATE pets SET name = 'Alterado por SQL', notes = 'Nota alterada por SQL' WHERE id = ?::uuid", rex);
		dayAgenda(tokenP, 7).andExpect(jsonPath("$[0].pet.name").value("Rex")).andExpect(jsonPath("$[0].pet.notes").value("Nota original"));
		send(get("/api/appointments/mine"), tokenA, null).andExpect(jsonPath("$[0].pet.name").value("Rex"));
	}

	// --- a cópia acompanha a edição só enquanto SCHEDULED ---

	/** Grava direto no banco um agendamento de HOJE (horário no meio do expediente) com a cópia dada. */
	private UUID insertToday(UUID petId, String name, String species, String breed, String size, String notes, String status) {
		return insertAt(day(0) + "T10:00:00", petId, name, species, breed, size, notes, status);
	}

	private UUID insertAt(String localStart, UUID petId, String name, String species, String breed, String size, String notes, String status) {
		var start = java.time.LocalDateTime.parse(localStart).atZone(ZONE).toInstant();
		return jdbc.queryForObject("INSERT INTO appointments (provider_id, service_id, customer_id, starts_at, ends_at, status, pet_id, pet_name, pet_species, pet_breed, pet_size, pet_notes) "
				+ "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id", UUID.class, providerP, serviceP, tutorA, java.sql.Timestamp.from(start),
				java.sql.Timestamp.from(start.plusSeconds(3600)), status, petId, name, species, breed, size, notes);
	}

	private void edit(String petId, String name, String notes) throws Exception {
		send(put("/api/customer/pets/" + petId), tokenA, PetApiIntegrationTest.body(name, "DOG", "Poodle", "LARGE", notes)).andExpect(status().isOk());
	}

	@Test
	void editarOPetAtualizaACopiaSoDosAgendamentosScheduledESobeAVersao() throws Exception {
		String rexId = pet(tokenA, "Rex", "DOG", "Poodle", "SMALL", "Nota antiga");
		UUID rex = UUID.fromString(rexId);
		UUID other = UUID.fromString(pet(tokenA, "Mel", "CAT", null, null, "Nota da Mel"));
		String scheduled = insertAt(day(7) + "T09:00:00", rex, "Rex", "DOG", "Poodle", "SMALL", "Nota antiga", "SCHEDULED").toString();
		String checkedIn = insertAt(day(7) + "T10:00:00", rex, "Rex", "DOG", "Poodle", "SMALL", "Nota antiga", "CHECKED_IN").toString();
		String completed = insertAt(day(7) + "T11:00:00", rex, "Rex", "DOG", "Poodle", "SMALL", "Nota antiga", "COMPLETED").toString();
		String cancelled = insertAt(day(7) + "T14:00:00", rex, "Rex", "DOG", "Poodle", "SMALL", "Nota antiga", "CANCELLED").toString();
		String ofOther = insertAt(day(7) + "T15:00:00", other, "Mel", "CAT", null, null, "Nota da Mel", "SCHEDULED").toString();
		long versionBefore = ((Number) row(scheduled).get("version")).longValue();

		edit(rexId, "Rex Jr", "Nota nova");

		assertEquals("Rex Jr", row(scheduled).get("pet_name"));
		assertEquals("Nota nova", row(scheduled).get("pet_notes"));
		assertEquals("LARGE", row(scheduled).get("pet_size"));
		assertEquals(versionBefore + 1, ((Number) row(scheduled).get("version")).longValue(), "a atualização em massa sobe a versão");
		for (String frozen : List.of(checkedIn, completed, cancelled)) {
			assertEquals("Rex", row(frozen).get("pet_name"), "depois do check-in a cópia nunca mais muda");
			assertEquals("Nota antiga", row(frozen).get("pet_notes"));
			assertEquals("SMALL", row(frozen).get("pet_size"));
		}
		assertEquals("Mel", row(ofOther).get("pet_name"), "agendamento de outro pet não é tocado");
		// e o prestador vê a edição no agendamento que ainda vai atender
		dayAgenda(tokenP, 7).andExpect(jsonPath("$[0].pet.name").value("Rex Jr")).andExpect(jsonPath("$[0].pet.notes").value("Nota nova"));
	}

	@Test
	void checkInDeVerdadeCongelaACopiaJaAtualizada() throws Exception {
		String rex = pet(tokenA, "Rex", "DOG", null, null, "Nota antiga");
		UUID scheduled = insertToday(UUID.fromString(rex), "Rex", "DOG", null, null, "Nota antiga", "SCHEDULED");
		edit(rex, "Rex Jr", "Nota nova"); // antes do check-in: a cópia acompanha
		send(post("/api/appointments/" + scheduled + "/check-in"), tokenAttendant, null).andExpect(status().isOk());
		edit(rex, "Rex Sênior", "Nota depois do check-in"); // depois do check-in: a cópia não muda mais
		assertEquals("CHECKED_IN", row(scheduled.toString()).get("status"));
		assertEquals("Rex Jr", row(scheduled.toString()).get("pet_name"));
		assertEquals("Nota nova", row(scheduled.toString()).get("pet_notes"));
		assertEquals("Rex Sênior", jdbc.queryForObject("SELECT name FROM pets WHERE id = ?::uuid", String.class, rex), "o cadastro continua sendo a fonte atual");
	}

	@Test
	void checkInQueLeuOAgendamentoAntesDaEdicaoEhRecusadoSemSobrescreverACopia() throws Exception {
		String rex = pet(tokenA, "Rex", "DOG", null, null, "Nota antiga");
		UUID scheduled = insertToday(UUID.fromString(rex), "Rex", "DOG", null, null, "Nota antiga", "SCHEDULED");
		Appointment stale = appointmentRepository.findById(scheduled).orElseThrow(); // lido ANTES da edição (fica desanexado)
		edit(rex, "Rex Jr", "Nota nova");

		stale.checkIn();
		assertThrows(OptimisticLockingFailureException.class, () -> appointmentRepository.saveAndFlush(stale), "o bloqueio otimista recusa a gravação obsoleta");
		Map<String, Object> row = row(scheduled.toString());
		assertEquals("SCHEDULED", row.get("status"));
		assertEquals("Rex Jr", row.get("pet_name"), "a cópia nova não foi sobrescrita pela entidade obsoleta");
		assertEquals("Nota nova", row.get("pet_notes"));
	}

	@Test
	void editarEmCorridaComCheckInQueVenceuDeixaACopiaComoEstava() throws Exception {
		String rex = pet(tokenA, "Rex", "DOG", null, null, "Nota antiga");
		UUID scheduled = insertToday(UUID.fromString(rex), "Rex", "DOG", null, null, "Nota antiga", "SCHEDULED");

		try (Connection checkIn = dataSource.getConnection()) {
			checkIn.setAutoCommit(false);
			try (var st = checkIn.prepareStatement("UPDATE appointments SET status = 'CHECKED_IN' WHERE id = ?")) { // check-in em andamento: segura a linha
				st.setObject(1, scheduled);
				st.executeUpdate();
			}
			ExecutorService pool = Executors.newSingleThreadExecutor();
			try {
				Future<MockHttpServletResponse> editing = pool.submit(() -> send(put("/api/customer/pets/" + rex), tokenA,
						PetApiIntegrationTest.body("Rex Jr", "DOG", null, null, "Nota nova")).andReturn().getResponse());
				assertThrows(java.util.concurrent.TimeoutException.class, () -> editing.get(700, TimeUnit.MILLISECONDS), "a edição espera a linha do agendamento");
				checkIn.commit(); // o check-in vence
				assertEquals(200, editing.get(10, TimeUnit.SECONDS).getStatus());
			} finally {
				pool.shutdownNow();
			}
		}
		Map<String, Object> row = row(scheduled.toString());
		assertEquals("CHECKED_IN", row.get("status"));
		assertEquals("Rex", row.get("pet_name"), "o status foi reavaliado depois da espera: o check-in venceu, a cópia ficou como estava");
		assertEquals("Nota antiga", row.get("pet_notes"));
		assertEquals("Rex Jr", jdbc.queryForObject("SELECT name FROM pets WHERE id = ?::uuid", String.class, rex), "o cadastro mudou");
	}

	// --- corrida reservar x arquivar ---

	@Test
	void reservaEsperaOArquivamentoEmAndamentoEFalhaComPetInativo() throws Exception {
		String rex = pet(tokenA, "Rex", "DOG", null, null, null);
		try (Connection archiving = dataSource.getConnection()) {
			archiving.setAutoCommit(false);
			try (var st = archiving.prepareStatement("UPDATE pets SET active = false WHERE id = ?::uuid")) { // arquivamento em andamento: segura a linha
				st.setString(1, rex);
				st.executeUpdate();
			}
			ExecutorService pool = Executors.newSingleThreadExecutor();
			try {
				Future<MockHttpServletResponse> booking = pool.submit(() -> book(tokenA, slot(7, 9), rex).andReturn().getResponse());
				assertThrows(java.util.concurrent.TimeoutException.class, () -> booking.get(700, TimeUnit.MILLISECONDS),
						"a reserva NÃO lê active sem bloquear: espera o arquivamento terminar");
				archiving.commit(); // o arquivamento vence
				MockHttpServletResponse response = booking.get(10, TimeUnit.SECONDS);
				assertEquals(422, response.getStatus());
				assertEquals("PET_INACTIVE", JsonPath.read(response.getContentAsString(), "$.reason"));
			} finally {
				pool.shutdownNow();
			}
		}
		assertEquals(0L, count("SELECT count(*) FROM appointments"), "nenhuma reserva foi gravada com o pet arquivado");
	}

	@Test
	void reservaEmAndamentoFazOArquivamentoEsperarEDepoisArquivaSemInvalidarAReserva() throws Exception {
		String rex = pet(tokenA, "Rex", "DOG", null, null, null);
		String id;
		try (Connection booking = dataSource.getConnection()) {
			booking.setAutoCommit(false);
			try (var st = booking.prepareStatement("SELECT id FROM pets WHERE id = ?::uuid FOR NO KEY UPDATE")) { // reserva em andamento: segura a linha do pet
				st.setString(1, rex);
				st.executeQuery();
			}
			ExecutorService pool = Executors.newSingleThreadExecutor();
			try {
				Future<MockHttpServletResponse> archiving = pool.submit(() -> send(post("/api/customer/pets/" + rex + "/archive"), tokenA, null).andReturn().getResponse());
				assertThrows(java.util.concurrent.TimeoutException.class, () -> archiving.get(700, TimeUnit.MILLISECONDS), "o arquivamento espera a reserva");
				booking.commit();
				assertEquals(200, archiving.get(10, TimeUnit.SECONDS).getStatus());
			} finally {
				pool.shutdownNow();
			}
		}
		assertFalse(jdbc.queryForObject("SELECT active FROM pets WHERE id = ?::uuid", Boolean.class, rex));
	}

	@Test
	void reservarEArquivarAoMesmoTempoNuncaDeixaReservaCom422OuArquivoSemReserva() throws Exception {
		for (int round = 0; round < 12; round++) {
			String petId = pet(tokenA, "Pet " + round, "DOG", null, null, null);
			String when = slot(8 + round, 9);
			ExecutorService pool = Executors.newFixedThreadPool(2);
			try {
				CountDownLatch go = new CountDownLatch(1);
				Future<MockHttpServletResponse> booking = pool.submit(() -> {
					go.await();
					return book(tokenA, when, petId).andReturn().getResponse();
				});
				Future<MockHttpServletResponse> archiving = pool.submit(() -> {
					go.await();
					return send(post("/api/customer/pets/" + petId + "/archive"), tokenA, null).andReturn().getResponse();
				});
				go.countDown();
				MockHttpServletResponse b = booking.get(30, TimeUnit.SECONDS);
				MockHttpServletResponse a = archiving.get(30, TimeUnit.SECONDS);
				assertEquals(200, a.getStatus(), "arquivar sempre dá certo");
				long reservas = count("SELECT count(*) FROM appointments WHERE pet_id = ?::uuid", petId);
				if (b.getStatus() == 201) {
					assertEquals(1L, reservas, "reserva confirmada existe, mesmo com o pet arquivado depois");
				} else {
					assertEquals(422, b.getStatus(), "a única recusa possível é pet inativo: " + b.getContentAsString());
					assertEquals("PET_INACTIVE", JsonPath.read(b.getContentAsString(), "$.reason"));
					assertEquals(0L, reservas, "reserva recusada não deixa nada gravado");
				}
			} finally {
				pool.shutdownNow();
			}
		}
	}
}
