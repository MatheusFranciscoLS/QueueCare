package com.queuecare.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;

/**
 * Base dos testes de banco: um PostgreSQL real, banco novo e migrado por teste, com dados mínimos:
 * dois prestadores (cada um com um serviço de 60 min) e um cliente.
 */
abstract class DatabaseTestBase {

	/** Código SQLSTATE do PostgreSQL para violação de restrição de exclusão. */
	static final String EXCLUSION_VIOLATION = "23P01";
	static final String CHECK_VIOLATION = "23514";
	static final String NOT_NULL_VIOLATION = "23502";
	static final String FOREIGN_KEY_VIOLATION = "23503";
	static final String UNIQUE_VIOLATION = "23505";

	DataSource dataSource;
	UUID provider1;
	UUID service1;
	UUID provider2;
	UUID service2;
	UUID customer;

	@FunctionalInterface
	interface SqlAction {
		void run() throws SQLException;
	}

	@BeforeEach
	void createDatabase() throws SQLException {
		dataSource = PostgresTestSupport.newMigratedDatabase();
		try (Connection c = dataSource.getConnection()) {
			UUID providerUser1 = PostgresTestSupport.insertUser(c, "prestador1@exemplo.test", "PROVIDER");
			UUID providerUser2 = PostgresTestSupport.insertUser(c, "prestador2@exemplo.test", "PROVIDER");
			customer = PostgresTestSupport.insertUser(c, "cliente@exemplo.test", "CUSTOMER");
			provider1 = PostgresTestSupport.insertProvider(c, providerUser1);
			provider2 = PostgresTestSupport.insertProvider(c, providerUser2);
			service1 = PostgresTestSupport.insertService(c, provider1, 60);
			service2 = PostgresTestSupport.insertService(c, provider2, 60);
		}
	}

	/** 16/11/2026 (segunda-feira) no horário informado, em UTC. */
	static Instant at(String hhmm) {
		return Instant.parse("2026-11-16T" + hhmm + ":00Z");
	}

	/** Executa a ação e confere que o PostgreSQL recusou com exatamente o SQLSTATE esperado. */
	static void assertSqlState(String expected, SqlAction action) {
		try {
			action.run();
		} catch (SQLException e) {
			assertEquals(expected, e.getSQLState(), "SQLSTATE inesperado: " + e.getMessage());
			return;
		}
		fail("esperava o SQLSTATE " + expected + ", mas a operação foi aceita");
	}
}
