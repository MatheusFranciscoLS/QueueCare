package com.queuecare.database;

import static com.queuecare.database.PostgresTestSupport.count;
import static com.queuecare.database.PostgresTestSupport.insertUser;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Vínculo atendente-prestador: as regras que o PRÓPRIO BANCO garante (V2), com PostgreSQL real. */
class StaffConstraintsTest extends DatabaseTestBase {

	private void execute(Connection c, String sql) throws SQLException {
		try (Statement statement = c.createStatement()) {
			statement.executeUpdate(sql);
		}
	}

	private void link(Connection c, UUID provider, UUID user) throws SQLException {
		execute(c, "INSERT INTO provider_staff (provider_id, user_id) VALUES ('" + provider + "', '" + user + "')");
	}

	@Test
	void atendenteEhVinculadoEAMigrationCriouATabela() throws SQLException {
		try (Connection c = dataSource.getConnection()) {
			UUID attendant = insertUser(c, "atendente@exemplo.test", "ATTENDANT");

			link(c, provider1, attendant);

			assertEquals(1, count(c, "SELECT count(*) FROM provider_staff WHERE role = 'ATTENDANT'"));
			assertEquals(1, count(c, "SELECT count(*) FROM flyway_schema_history WHERE version = '2' AND success"));
		}
	}

	@Test
	void soAtendentePodeSerVinculado() throws SQLException {
		try (Connection c = dataSource.getConnection()) {
			UUID providerUser = insertUser(c, "outro-prestador@exemplo.test", "PROVIDER");

			// Cliente e prestador são recusados pela FK composta (user_id, role) -> users(id, role).
			assertSqlState(FOREIGN_KEY_VIOLATION, () -> link(c, provider1, customer));
			assertSqlState(FOREIGN_KEY_VIOLATION, () -> link(c, provider1, providerUser));
			// Gravar outro perfil na própria linha também é recusado (CHECK).
			assertSqlState(CHECK_VIOLATION, () -> execute(c,
					"INSERT INTO provider_staff (provider_id, user_id, role) VALUES ('" + provider1 + "', '" + providerUser + "', 'PROVIDER')"));
			assertEquals(0, count(c, "SELECT count(*) FROM provider_staff"));
		}
	}

	@Test
	void umAtendentePertenceANoMaximoUmPrestador() throws SQLException {
		try (Connection c = dataSource.getConnection()) {
			UUID attendant = insertUser(c, "atendente@exemplo.test", "ATTENDANT");
			link(c, provider1, attendant);

			assertSqlState(UNIQUE_VIOLATION, () -> link(c, provider2, attendant));
			assertSqlState(UNIQUE_VIOLATION, () -> link(c, provider1, attendant));
			assertEquals(1, count(c, "SELECT count(*) FROM provider_staff"));
		}
	}

	@Test
	void perfilDeUsuarioVinculadoNaoMudaENemOUsuarioEApagado() throws SQLException {
		try (Connection c = dataSource.getConnection()) {
			UUID attendant = insertUser(c, "atendente@exemplo.test", "ATTENDANT");
			link(c, provider1, attendant);

			assertSqlState(FOREIGN_KEY_VIOLATION, () -> execute(c, "UPDATE users SET role = 'PROVIDER' WHERE id = '" + attendant + "'"));
			assertSqlState(FOREIGN_KEY_VIOLATION, () -> execute(c, "UPDATE users SET role = 'CUSTOMER' WHERE id = '" + attendant + "'"));
			assertSqlState(FOREIGN_KEY_VIOLATION, () -> execute(c, "DELETE FROM users WHERE id = '" + attendant + "'"));
			assertEquals("ATTENDANT", PostgresTestSupport.scalar(c, "SELECT role FROM users WHERE id = '" + attendant + "'"));

			// Depois de desvincular, a mudança de perfil volta a ser possível.
			execute(c, "DELETE FROM provider_staff WHERE user_id = '" + attendant + "'");
			execute(c, "UPDATE users SET role = 'PROVIDER' WHERE id = '" + attendant + "'");
		}
	}

	@Test
	void prestadorComAtendenteVinculadoNaoEApagado() throws SQLException {
		try (Connection c = dataSource.getConnection()) {
			link(c, provider1, insertUser(c, "atendente@exemplo.test", "ATTENDANT"));

			assertSqlState(FOREIGN_KEY_VIOLATION, () -> execute(c, "DELETE FROM providers WHERE id = '" + provider1 + "'"));
		}
	}

	@Test
	void camposObrigatoriosEReferenciasExistentes() throws SQLException {
		try (Connection c = dataSource.getConnection()) {
			UUID attendant = insertUser(c, "atendente@exemplo.test", "ATTENDANT");

			assertSqlState(NOT_NULL_VIOLATION, () -> execute(c, "INSERT INTO provider_staff (provider_id, user_id) VALUES (NULL, '" + attendant + "')"));
			assertSqlState(NOT_NULL_VIOLATION, () -> execute(c, "INSERT INTO provider_staff (provider_id, user_id) VALUES ('" + provider1 + "', NULL)"));
			assertSqlState(FOREIGN_KEY_VIOLATION, () -> link(c, UUID.randomUUID(), attendant));
			assertSqlState(FOREIGN_KEY_VIOLATION, () -> link(c, provider1, UUID.randomUUID()));
		}
	}
}
