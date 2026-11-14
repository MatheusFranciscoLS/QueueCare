package com.queuecare.database;

import static com.queuecare.database.PostgresTestSupport.count;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.Test;

/** Restrições das demais tabelas (usuários, prestadores, serviços e janelas de atendimento) no PostgreSQL real. */
class SchemaConstraintsTest extends DatabaseTestBase {

	private void execute(Connection c, String sql) throws SQLException {
		try (Statement statement = c.createStatement()) {
			statement.executeUpdate(sql);
		}
	}

	// --- migration ---

	@Test
	void migrationCriaTodasAsTabelas() throws SQLException {
		try (Connection c = dataSource.getConnection()) {
			assertEquals(6, count(c, "SELECT count(*) FROM information_schema.tables WHERE table_schema = 'public' "
					+ "AND table_name IN ('users','providers','services','availability_windows','appointments','appointment_events')"));
			assertEquals(1, count(c, "SELECT count(*) FROM flyway_schema_history WHERE version = '1' AND success"));
		}
	}

	// --- users ---

	@Test
	void emailDuplicadoEhRecusadoSemDiferenciarMaiusculas() throws SQLException {
		try (Connection c = dataSource.getConnection()) {
			assertSqlState(UNIQUE_VIOLATION, () -> PostgresTestSupport.insertUser(c, "CLIENTE@EXEMPLO.TEST", "CUSTOMER"));
		}
	}

	@Test
	void perfilInvalidoEhRecusado() throws SQLException {
		try (Connection c = dataSource.getConnection()) {
			assertSqlState(CHECK_VIOLATION, () -> PostgresTestSupport.insertUser(c, "outro@exemplo.test", "ROOT"));
		}
	}

	@Test
	void camposObrigatoriosDeUsuarioNaoAceitamNulo() throws SQLException {
		try (Connection c = dataSource.getConnection()) {
			assertSqlState(NOT_NULL_VIOLATION, () -> execute(c, "INSERT INTO users (name, email, password_hash, role) VALUES (NULL, 'a@exemplo.test', 'h', 'CUSTOMER')"));
			assertSqlState(NOT_NULL_VIOLATION, () -> execute(c, "INSERT INTO users (name, email, password_hash, role) VALUES ('A', NULL, 'h', 'CUSTOMER')"));
			assertSqlState(NOT_NULL_VIOLATION, () -> execute(c, "INSERT INTO users (name, email, password_hash, role) VALUES ('A', 'a@exemplo.test', NULL, 'CUSTOMER')"));
			assertSqlState(NOT_NULL_VIOLATION, () -> execute(c, "INSERT INTO users (name, email, password_hash, role) VALUES ('A', 'a@exemplo.test', 'h', NULL)"));
		}
	}

	// --- providers ---

	@Test
	void cadaUsuarioTemNoMaximoUmPrestador() throws SQLException {
		try (Connection c = dataSource.getConnection()) {
			UserIds ids = new UserIds(c);
			assertSqlState(UNIQUE_VIOLATION, () -> PostgresTestSupport.insertProvider(c, ids.providerUser1()));
		}
	}

	@Test
	void configuracoesNegativasDoPrestadorSaoRecusadas() throws SQLException {
		try (Connection c = dataSource.getConnection()) {
			java.util.UUID user = PostgresTestSupport.insertUser(c, "novo@exemplo.test", "PROVIDER");
			assertSqlState(CHECK_VIOLATION, () -> execute(c,
					"INSERT INTO providers (user_id, business_name, time_zone, minimum_notice_minutes) VALUES ('" + user + "', 'X', 'America/Sao_Paulo', -1)"));
			assertSqlState(CHECK_VIOLATION, () -> execute(c,
					"INSERT INTO providers (user_id, business_name, time_zone, cancellation_deadline_minutes) VALUES ('" + user + "', 'X', 'America/Sao_Paulo', -1)"));
			assertSqlState(NOT_NULL_VIOLATION, () -> execute(c,
					"INSERT INTO providers (user_id, business_name, time_zone) VALUES ('" + user + "', 'X', NULL)"));
		}
	}

	@Test
	void prestadorExigeUsuarioExistente() throws SQLException {
		try (Connection c = dataSource.getConnection()) {
			assertSqlState(FOREIGN_KEY_VIOLATION, () -> execute(c,
					"INSERT INTO providers (user_id, business_name, time_zone) VALUES (gen_random_uuid(), 'X', 'America/Sao_Paulo')"));
		}
	}

	// --- services ---

	@Test
	void duracaoDoServicoPrecisaSerPositiva() throws SQLException {
		try (Connection c = dataSource.getConnection()) {
			assertSqlState(CHECK_VIOLATION, () -> PostgresTestSupport.insertService(c, provider1, 0));
			assertSqlState(CHECK_VIOLATION, () -> PostgresTestSupport.insertService(c, provider1, -30));
		}
	}

	// --- availability_windows ---

	private void window(Connection c, java.util.UUID provider, int day, String from, String to) throws SQLException {
		execute(c, "INSERT INTO availability_windows (provider_id, day_of_week, start_time, end_time) VALUES ('"
				+ provider + "', " + day + ", '" + from + "', '" + to + "')");
	}

	@Test
	void janelasSobrepostasNoMesmoDiaSaoRecusadas() throws SQLException {
		try (Connection c = dataSource.getConnection()) {
			window(c, provider1, 1, "08:00", "12:00");
			assertSqlState(EXCLUSION_VIOLATION, () -> window(c, provider1, 1, "11:59", "18:00"));
			assertSqlState(EXCLUSION_VIOLATION, () -> window(c, provider1, 1, "09:00", "10:00"));
		}
	}

	@Test
	void janelasColadasDiasDiferentesEOutroPrestadorSaoAceitos() throws SQLException {
		try (Connection c = dataSource.getConnection()) {
			window(c, provider1, 1, "08:00", "12:00");
			window(c, provider1, 1, "12:00", "18:00");
			window(c, provider1, 2, "08:00", "12:00");
			window(c, provider2, 1, "08:00", "12:00");
			assertEquals(4, count(c, "SELECT count(*) FROM availability_windows"));
		}
	}

	@Test
	void janelaInvalidaEhRecusada() throws SQLException {
		try (Connection c = dataSource.getConnection()) {
			assertSqlState(CHECK_VIOLATION, () -> window(c, provider1, 1, "12:00", "12:00"));
			assertSqlState(CHECK_VIOLATION, () -> window(c, provider1, 1, "18:00", "08:00"));
			assertSqlState(CHECK_VIOLATION, () -> window(c, provider1, 0, "08:00", "12:00"));
			assertSqlState(CHECK_VIOLATION, () -> window(c, provider1, 8, "08:00", "12:00"));
			assertSqlState(NOT_NULL_VIOLATION, () -> execute(c,
					"INSERT INTO availability_windows (provider_id, day_of_week, start_time, end_time) VALUES ('" + provider1 + "', 1, NULL, '12:00')"));
		}
	}

	/** Usuários criados pelo cenário-base, para os testes que precisam do id do primeiro prestador. */
	private final class UserIds {
		private final java.util.UUID providerUser1;

		UserIds(Connection c) throws SQLException {
			try (Statement s = c.createStatement(); java.sql.ResultSet rs = s.executeQuery("SELECT user_id FROM providers WHERE id = '" + provider1 + "'")) {
				rs.next();
				providerUser1 = rs.getObject(1, java.util.UUID.class);
			}
		}

		java.util.UUID providerUser1() {
			return providerUser1;
		}
	}
}
