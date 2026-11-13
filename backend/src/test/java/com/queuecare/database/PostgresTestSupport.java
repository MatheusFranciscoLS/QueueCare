package com.queuecare.database;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;

/**
 * Apoio dos testes de banco: sobe UM PostgreSQL real (embutido, versão 16, sem Docker) por execução de testes
 * e entrega um banco novo, já migrado pelo Flyway, para cada teste. O H2 não serve aqui: constraints de
 * exclusão e concorrência só se provam com PostgreSQL de verdade.
 */
public final class PostgresTestSupport {

	private static final EmbeddedPostgres SERVER = startServer();
	private static final AtomicInteger COUNTER = new AtomicInteger();

	private PostgresTestSupport() {
	}

	private static EmbeddedPostgres startServer() {
		try {
			EmbeddedPostgres server = EmbeddedPostgres.builder().start();
			Runtime.getRuntime().addShutdownHook(new Thread(() -> {
				try {
					server.close();
				} catch (IOException ignored) {
					// encerrando a JVM
				}
			}));
			return server;
		} catch (IOException e) {
			throw new IllegalStateException("não foi possível iniciar o PostgreSQL embutido", e);
		}
	}

	/** Cria um banco novo no servidor, aplica as migrations do projeto e devolve o DataSource dele. */
	static DataSource newMigratedDatabase() throws SQLException {
		String name = "queuecare_test_" + COUNTER.incrementAndGet();
		try (Connection connection = SERVER.getPostgresDatabase().getConnection(); Statement statement = connection.createStatement()) {
			statement.execute("CREATE DATABASE " + name);
		}
		DataSource dataSource = SERVER.getDatabase("postgres", name);
		Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
		return dataSource;
	}

	/** Cria um banco novo (vazio, sem migrations) e devolve a URL JDBC; usado pelos testes que sobem o Spring, que migra sozinho. */
	public static String newEmptyDatabaseJdbcUrl() throws SQLException {
		String name = "queuecare_spring_" + COUNTER.incrementAndGet();
		try (Connection connection = SERVER.getPostgresDatabase().getConnection(); Statement statement = connection.createStatement()) {
			statement.execute("CREATE DATABASE " + name);
		}
		return SERVER.getJdbcUrl("postgres", name);
	}

	// --- helpers de dados (JDBC puro, para não depender de JPA/Hibernate) ---

	static UUID insertUser(Connection c, String email, String role) throws SQLException {
		try (PreparedStatement ps = c.prepareStatement(
				"INSERT INTO users (name, email, password_hash, role) VALUES ('Fulano de Teste', ?, 'hash-ficticio', ?) RETURNING id")) {
			ps.setString(1, email);
			ps.setString(2, role);
			return queryId(ps);
		}
	}

	static UUID insertProvider(Connection c, UUID userId) throws SQLException {
		try (PreparedStatement ps = c.prepareStatement(
				"INSERT INTO providers (user_id, business_name, time_zone) VALUES (?, 'Oficina de Teste', 'America/Sao_Paulo') RETURNING id")) {
			ps.setObject(1, userId);
			return queryId(ps);
		}
	}

	static UUID insertService(Connection c, UUID providerId, int durationMinutes) throws SQLException {
		try (PreparedStatement ps = c.prepareStatement(
				"INSERT INTO services (provider_id, name, duration_minutes) VALUES (?, 'Serviço de Teste', ?) RETURNING id")) {
			ps.setObject(1, providerId);
			ps.setInt(2, durationMinutes);
			return queryId(ps);
		}
	}

	static UUID insertAppointment(Connection c, UUID providerId, UUID serviceId, UUID customerId, Instant start, Instant end, String status)
			throws SQLException {
		try (PreparedStatement ps = c.prepareStatement(
				"INSERT INTO appointments (provider_id, service_id, customer_id, starts_at, ends_at, status) VALUES (?, ?, ?, ?, ?, ?) RETURNING id")) {
			ps.setObject(1, providerId);
			ps.setObject(2, serviceId);
			ps.setObject(3, customerId);
			ps.setObject(4, OffsetDateTime.ofInstant(start, ZoneOffset.UTC));
			ps.setObject(5, OffsetDateTime.ofInstant(end, ZoneOffset.UTC));
			ps.setString(6, status);
			return queryId(ps);
		}
	}

	static long count(Connection c, String sql) throws SQLException {
		try (Statement statement = c.createStatement(); ResultSet rs = statement.executeQuery(sql)) {
			rs.next();
			return rs.getLong(1);
		}
	}

	static String scalar(Connection c, String sql) throws SQLException {
		try (Statement statement = c.createStatement(); ResultSet rs = statement.executeQuery(sql)) {
			rs.next();
			return rs.getString(1);
		}
	}

	private static UUID queryId(PreparedStatement ps) throws SQLException {
		try (ResultSet rs = ps.executeQuery()) {
			rs.next();
			return rs.getObject(1, UUID.class);
		}
	}
}
