package com.queuecare.database;

import static com.queuecare.database.PostgresTestSupport.count;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/**
 * V10 (ADR 0016, PR 49): todo agendamento NOVO tem pet; os que já existiam continuam sem. A marca {@code legacy_without_pet} é escrita só
 * pela migration e protegida por gatilho: ninguém "se declara antigo", nem retroagindo uma data. PostgreSQL real, por SQL direto.
 */
class PetRequiredConstraintsTest extends DatabaseTestBase {

	private int next = 9;

	private UUID pet() throws SQLException {
		try (Connection c = dataSource.getConnection()) {
			return PostgresTestSupport.insertPet(c, customer);
		}
	}

	/** INSERT de agendamento com os campos de pet e a marca dadas (nulos = não informados), e a data de criação opcional. */
	private UUID insert(Connection c, UUID petId, Boolean legacy, String createdAt) throws SQLException {
		Instant start = at(String.format("%02d:00", next++));
		StringBuilder cols = new StringBuilder("provider_id, service_id, customer_id, starts_at, ends_at, status");
		StringBuilder vals = new StringBuilder("?, ?, ?, ?, ?, 'SCHEDULED'");
		if (petId != null) {
			cols.append(", pet_id, pet_name, pet_species");
			vals.append(", ?, 'Rex', 'DOG'");
		}
		if (legacy != null) {
			cols.append(", legacy_without_pet");
			vals.append(", ?");
		}
		if (createdAt != null) {
			cols.append(", created_at");
			vals.append(", ?::timestamptz");
		}
		try (PreparedStatement ps = c.prepareStatement("INSERT INTO appointments (" + cols + ") VALUES (" + vals + ") RETURNING id")) {
			int i = 1;
			ps.setObject(i++, provider1);
			ps.setObject(i++, service1);
			ps.setObject(i++, customer);
			ps.setTimestamp(i++, Timestamp.from(start));
			ps.setTimestamp(i++, Timestamp.from(start.plusSeconds(3600)));
			if (petId != null) {
				ps.setObject(i++, petId);
			}
			if (legacy != null) {
				ps.setBoolean(i++, legacy);
			}
			if (createdAt != null) {
				ps.setString(i, createdAt);
			}
			try (var rs = ps.executeQuery()) {
				rs.next();
				return rs.getObject(1, UUID.class);
			}
		}
	}

	@Test
	void migrationV10CriaAMarcaEORestoDoEsquemaContinua() throws SQLException {
		try (Connection c = dataSource.getConnection()) {
			assertEquals(1, count(c, "SELECT count(*) FROM flyway_schema_history WHERE version = '10' AND success"));
			assertEquals(1, count(c, "SELECT count(*) FROM information_schema.columns WHERE table_name = 'appointments' AND column_name = 'legacy_without_pet'"));
			assertEquals(1, count(c, "SELECT count(*) FROM pg_trigger WHERE tgname = 'appointments_legacy_guard' AND NOT tgisinternal"));
		}
	}

	@Test
	void agendamentoNovoSemPetEhRecusadoPeloBanco() throws SQLException {
		try (Connection c = dataSource.getConnection()) {
			assertSqlState(CHECK_VIOLATION, () -> insert(c, null, null, null));
			assertEquals(0, count(c, "SELECT count(*) FROM appointments"));
		}
	}

	@Test
	void agendamentoNovoComPetEAceitoEAMarcaFicaFalsa() throws SQLException {
		UUID petId = pet();
		try (Connection c = dataSource.getConnection()) {
			insert(c, petId, null, null);
			assertEquals(1, count(c, "SELECT count(*) FROM appointments WHERE pet_id IS NOT NULL AND NOT legacy_without_pet"));
		}
	}

	@Test
	void ninguemSeDeclaraAntigoNemRetroagindoADataDeCriacao() throws SQLException {
		UUID petId = pet();
		try (Connection c = dataSource.getConnection()) {
			// a marca verdadeira no INSERT é recusada pelo gatilho, com ou sem pet
			assertSqlState(CHECK_VIOLATION, () -> insert(c, null, true, null));
			assertSqlState(CHECK_VIOLATION, () -> insert(c, petId, true, null));
			// uma data de criação anterior à migration NÃO escapa da regra: ela não depende de data nem de relógio
			assertSqlState(CHECK_VIOLATION, () -> insert(c, null, null, "2000-01-01T00:00:00Z"));
			assertEquals(0, count(c, "SELECT count(*) FROM appointments"));
		}
	}

	@Test
	void aMarcaNaoPodeSerAlteradaEmLinhaExistente() throws SQLException {
		UUID petId = pet();
		try (Connection c = dataSource.getConnection()) {
			UUID id = insert(c, petId, null, null);
			try (PreparedStatement ps = c.prepareStatement("UPDATE appointments SET legacy_without_pet = true WHERE id = ?")) {
				ps.setObject(1, id);
				assertSqlState(CHECK_VIOLATION, ps::executeUpdate);
			}
			// mudar outras colunas continua permitido (o gatilho só guarda a marca)
			try (PreparedStatement ps = c.prepareStatement("UPDATE appointments SET status = 'CHECKED_IN' WHERE id = ?")) {
				ps.setObject(1, id);
				assertEquals(1, ps.executeUpdate());
			}
		}
	}

	/** Cria, no esquema da V9, usuários, prestador, serviço e dois agendamentos: um SEM pet (antigo) e um COM pet. */
	private record Old(UUID oldWithoutPet, UUID oldWithPet) {
	}

	private Old seedVersion9(DataSource ds) throws SQLException {
		try (Connection c = ds.getConnection()) {
			UUID owner = PostgresTestSupport.insertUser(c, "prestador-antigo@exemplo.test", "PROVIDER");
			UUID tutor = PostgresTestSupport.insertUser(c, "tutor-antigo@exemplo.test", "CUSTOMER");
			UUID provider = PostgresTestSupport.insertProvider(c, owner);
			UUID service = PostgresTestSupport.insertService(c, provider, 60);
			UUID pet;
			try (PreparedStatement ps = c.prepareStatement("INSERT INTO pets (customer_id, name, species) VALUES (?, 'Rex', 'DOG') RETURNING id")) {
				ps.setObject(1, tutor);
				try (var rs = ps.executeQuery()) {
					rs.next();
					pet = rs.getObject(1, UUID.class);
				}
			}
			UUID withoutPet = rawAppointment(c, provider, service, tutor, "10:00", null);
			UUID withPet = rawAppointment(c, provider, service, tutor, "11:00", pet);
			return new Old(withoutPet, withPet);
		}
	}

	private UUID rawAppointment(Connection c, UUID provider, UUID service, UUID tutor, String hhmm, UUID petId) throws SQLException {
		Instant start = at(hhmm);
		String sql = petId == null
				? "INSERT INTO appointments (provider_id, service_id, customer_id, starts_at, ends_at, status) VALUES (?, ?, ?, ?, ?, 'SCHEDULED') RETURNING id"
				: "INSERT INTO appointments (provider_id, service_id, customer_id, starts_at, ends_at, status, pet_id, pet_name, pet_species) VALUES (?, ?, ?, ?, ?, 'SCHEDULED', ?, 'Rex', 'DOG') RETURNING id";
		try (PreparedStatement ps = c.prepareStatement(sql)) {
			ps.setObject(1, provider);
			ps.setObject(2, service);
			ps.setObject(3, tutor);
			ps.setTimestamp(4, Timestamp.from(start));
			ps.setTimestamp(5, Timestamp.from(start.plusSeconds(3600)));
			if (petId != null) {
				ps.setObject(6, petId);
			}
			try (var rs = ps.executeQuery()) {
				rs.next();
				return rs.getObject(1, UUID.class);
			}
		}
	}

	@Test
	void migrationSobreBancoComDadosMarcaAsLinhasAntigasESoElasEElasContinuamFuncionando() throws SQLException {
		DataSource old = PostgresTestSupport.newDatabaseMigratedTo("9");
		Old rows = seedVersion9(old);

		PostgresTestSupport.migrateToLatest(old); // a V10 roda sobre dados que já existiam

		try (Connection c = old.getConnection()) {
			assertEquals(1, count(c, "SELECT count(*) FROM appointments WHERE legacy_without_pet"), "só o agendamento antigo SEM pet é marcado");
			assertEquals(1, count(c, "SELECT count(*) FROM appointments WHERE id = '" + rows.oldWithoutPet() + "' AND legacy_without_pet AND pet_id IS NULL"));
			assertEquals(1, count(c, "SELECT count(*) FROM appointments WHERE id = '" + rows.oldWithPet() + "' AND NOT legacy_without_pet AND pet_id IS NOT NULL"));
			// o agendamento antigo continua podendo mudar de estado (a regra o dispensa pela marca, não por data)
			try (Statement st = c.createStatement()) {
				assertEquals(1, st.executeUpdate("UPDATE appointments SET status = 'CHECKED_IN' WHERE id = '" + rows.oldWithoutPet() + "'"));
				assertEquals(1, st.executeUpdate("UPDATE appointments SET status = 'COMPLETED' WHERE id = '" + rows.oldWithoutPet() + "'"));
			}
			// mas a marca dele não muda, e agendamento novo sem pet continua recusado
			try (Statement st = c.createStatement()) {
				assertSqlState(CHECK_VIOLATION, () -> st.executeUpdate("UPDATE appointments SET legacy_without_pet = false WHERE id = '" + rows.oldWithoutPet() + "'"));
			}
			UUID provider = jdbcUuid(c, "SELECT id FROM providers");
			UUID service = jdbcUuid(c, "SELECT id FROM services");
			UUID tutor = jdbcUuid(c, "SELECT id FROM users WHERE role = 'CUSTOMER'");
			assertSqlState(CHECK_VIOLATION, () -> rawAppointment(c, provider, service, tutor, "14:00", null));
		}
	}

	private static UUID jdbcUuid(Connection c, String sql) throws SQLException {
		try (Statement st = c.createStatement(); var rs = st.executeQuery(sql)) {
			rs.next();
			return rs.getObject(1, UUID.class);
		}
	}
}
