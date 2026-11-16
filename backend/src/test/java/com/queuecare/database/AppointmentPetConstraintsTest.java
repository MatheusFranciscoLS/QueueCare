package com.queuecare.database;

import static com.queuecare.database.PostgresTestSupport.count;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Restrições da V9 (ADR 0016, item 2): o agendamento só usa pet do MESMO cliente (chave estrangeira composta) e a cópia dos dados do
 * pet é consistente e na forma normalizada. Tudo por SQL direto, no PostgreSQL real: o banco protege mesmo se a aplicação errar.
 */
class AppointmentPetConstraintsTest extends DatabaseTestBase {

	UUID otherCustomer;
	UUID myPet;
	UUID otherPet;

	@BeforeEach
	void seedPets() throws SQLException {
		try (Connection c = dataSource.getConnection()) {
			otherCustomer = PostgresTestSupport.insertUser(c, "outro-cliente@exemplo.test", "CUSTOMER");
			myPet = insertPet(c, customer, "Rex");
			otherPet = insertPet(c, otherCustomer, "Mel");
		}
	}

	private UUID insertPet(Connection c, UUID owner, String name) throws SQLException {
		try (PreparedStatement ps = c.prepareStatement("INSERT INTO pets (customer_id, name, species) VALUES (?, ?, 'DOG') RETURNING id")) {
			ps.setObject(1, owner);
			ps.setString(2, name);
			try (var rs = ps.executeQuery()) {
				rs.next();
				return rs.getObject(1, UUID.class);
			}
		}
	}

	/** Agendamento do cliente (customer) às 9h com os campos de pet dados; o horário muda a cada chamada para não colidir. */
	private int next = 9;

	private void insertAppointment(Connection c, UUID petId, String name, String species, String breed, String size, String notes) throws SQLException {
		Instant start = at(String.format("%02d:00", next++));
		try (PreparedStatement ps = c.prepareStatement("INSERT INTO appointments (provider_id, service_id, customer_id, starts_at, ends_at, status, "
				+ "pet_id, pet_name, pet_species, pet_breed, pet_size, pet_notes) VALUES (?, ?, ?, ?, ?, 'SCHEDULED', ?, ?, ?, ?, ?, ?)")) {
			ps.setObject(1, provider1);
			ps.setObject(2, service1);
			ps.setObject(3, customer);
			ps.setTimestamp(4, Timestamp.from(start));
			ps.setTimestamp(5, Timestamp.from(start.plusSeconds(3600)));
			ps.setObject(6, petId);
			ps.setString(7, name);
			ps.setString(8, species);
			ps.setString(9, breed);
			ps.setString(10, size);
			ps.setString(11, notes);
			ps.executeUpdate();
		}
	}

	private void rejected(String expectedState, UUID petId, String name, String species, String breed, String size, String notes) throws SQLException {
		try (Connection c = dataSource.getConnection()) {
			assertSqlState(expectedState, () -> insertAppointment(c, petId, name, species, breed, size, notes));
		}
	}

	@Test
	void migrationV9AcrescentaAsColunasDoPet() throws SQLException {
		try (Connection c = dataSource.getConnection()) {
			assertEquals(1, count(c, "SELECT count(*) FROM flyway_schema_history WHERE version = '9' AND success"));
			assertEquals(6, count(c, "SELECT count(*) FROM information_schema.columns WHERE table_name = 'appointments' "
					+ "AND column_name IN ('pet_id','pet_name','pet_species','pet_breed','pet_size','pet_notes')"));
		}
	}

	@Test
	void agendamentoSemPetContinuaValidoELinhasAntigasFicamNulas() throws SQLException {
		try (Connection c = dataSource.getConnection()) {
			insertAppointment(c, null, null, null, null, null, null);
			assertEquals(1, count(c, "SELECT count(*) FROM appointments WHERE pet_id IS NULL AND pet_name IS NULL AND pet_species IS NULL"));
		}
	}

	@Test
	void agendamentoComPetDoProprioClienteEAceitoComACopia() throws SQLException {
		try (Connection c = dataSource.getConnection()) {
			insertAppointment(c, myPet, "Rex", "DOG", "Poodle", "SMALL", "Tem medo de secador.\nNão gosta de cheiro forte.");
			insertAppointment(c, myPet, "Rex", "DOG", null, null, null); // raça, porte e observações são opcionais
			assertEquals(2, count(c, "SELECT count(*) FROM appointments WHERE pet_id IS NOT NULL"));
		}
	}

	@Test
	void petDeOutroClienteEhRecusadoPeloBancoMesmoComACopiaCorreta() throws SQLException {
		rejected(FOREIGN_KEY_VIOLATION, otherPet, "Mel", "DOG", null, null, null);
		rejected(FOREIGN_KEY_VIOLATION, UUID.randomUUID(), "Fantasma", "DOG", null, null, null);
		try (Connection c = dataSource.getConnection()) {
			assertEquals(0, count(c, "SELECT count(*) FROM appointments"));
		}
	}

	@Test
	void petComReservasNaoPodeSerApagado() throws SQLException {
		try (Connection c = dataSource.getConnection()) {
			insertAppointment(c, myPet, "Rex", "DOG", null, null, null);
			try (PreparedStatement ps = c.prepareStatement("DELETE FROM pets WHERE id = ?")) {
				ps.setObject(1, myPet);
				assertSqlState(FOREIGN_KEY_VIOLATION, ps::executeUpdate);
			}
		}
	}

	@Test
	void copiaInconsistenteComOPetEhRecusada() throws SQLException {
		rejected(CHECK_VIOLATION, myPet, null, "DOG", null, null, null); // pet sem nome
		rejected(CHECK_VIOLATION, myPet, "Rex", null, null, null, null); // pet sem espécie
		rejected(CHECK_VIOLATION, null, "Rex", "DOG", null, null, null); // cópia sem pet
		rejected(CHECK_VIOLATION, null, null, null, "Poodle", null, null); // raça solta
		rejected(CHECK_VIOLATION, null, null, null, null, "SMALL", null); // porte solto
		rejected(CHECK_VIOLATION, null, null, null, null, null, "texto solto"); // observações soltas
	}

	@Test
	void copiaForaDaFormaNormalizadaEhRecusada() throws SQLException {
		rejected(CHECK_VIOLATION, myPet, "Rex  Jr", "DOG", null, null, null); // espaço duplo
		rejected(CHECK_VIOLATION, myPet, " Rex", "DOG", null, null, null);
		rejected(CHECK_VIOLATION, myPet, "Rex\tJr", "DOG", null, null, null);
		rejected(CHECK_VIOLATION, myPet, "Rex", "BIRD", null, null, null);
		rejected(CHECK_VIOLATION, myPet, "Rex", "DOG", null, "HUGE", null);
		rejected(CHECK_VIOLATION, myPet, "Rex", "DOG", "", null, null);
		rejected(CHECK_VIOLATION, myPet, "Rex", "DOG", "Pastor  Alemão", null, null);
		rejected(CHECK_VIOLATION, myPet, "Rex", "DOG", null, null, "");
		rejected(CHECK_VIOLATION, myPet, "Rex", "DOG", null, null, "texto\n"); // quebra de linha no fim
		rejected(CHECK_VIOLATION, myPet, "Rex", "DOG", null, null, "linha 1\r\nlinha 2");
		rejected(CHECK_VIOLATION, myPet, "Rex", "DOG", null, null, "sino\u0007");
		rejected(CHECK_VIOLATION, myPet, "Rex", "DOG", null, null, "nel\u0085");
	}

	@Test
	void copiaUsaOLimiteDeTamanhoDasColunas() throws SQLException {
		rejected("22001", myPet, "x".repeat(81), "DOG", null, null, null);
		rejected("22001", myPet, "Rex", "DOG", "x".repeat(81), null, null);
		rejected("22001", myPet, "Rex", "DOG", null, null, "x".repeat(501));
	}
}
