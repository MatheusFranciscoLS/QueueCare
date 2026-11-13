package com.queuecare.scheduling.application;

import com.queuecare.database.PostgresTestSupport;
import java.sql.SQLException;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Base dos testes de integração do agendamento: aplicação Spring COMPLETA (JPA, Flyway, serviço) contra um PostgreSQL
 * real embutido, com o esquema validado pelo Hibernate ({@code ddl-auto: validate}) e o relógio fixo.
 * Os dados de cada teste são semeados por JDBC e apagados antes de cada teste.
 */
@SpringBootTest
@Import(FixedClockConfig.class)
abstract class BookingIntegrationTestBase {

	@DynamicPropertySource
	static void database(DynamicPropertyRegistry registry) throws SQLException {
		String url = PostgresTestSupport.newEmptyDatabaseJdbcUrl();
		registry.add("spring.datasource.url", () -> url);
		registry.add("spring.datasource.username", () -> "postgres");
		registry.add("spring.datasource.password", () -> "teste");
		registry.add("spring.flyway.enabled", () -> "true");
		registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
		registry.add("queuecare.jwt.secret", () -> "dGVzdGUtZXhjbHVzaXZvLWRlLXRlc3RlLTMyLWJ5dGVzLSEh");
		registry.add("queuecare.security.bcrypt-strength", () -> "4");
	}

	@Autowired
	JdbcTemplate jdbc;

	UUID customer;
	/** Prestador de São Paulo (UTC-3, sem horário de verão), antecedência de 60 min, janelas todos os dias 08-12 e 14-18. */
	UUID providerSp;
	UUID serviceSp;
	/** Prestador de Nova York, sem antecedência, janela de domingo 00:00-06:00 (para as datas de horário de verão de 2027). */
	UUID providerNy;
	UUID serviceNy;

	@BeforeEach
	void seed() {
		jdbc.execute("TRUNCATE appointment_events, appointments, availability_windows, services, providers, users CASCADE");
		customer = user("cliente@exemplo.test", "CUSTOMER", true);
		providerSp = provider(user("prestador-sp@exemplo.test", "PROVIDER", true), "America/Sao_Paulo", 60);
		serviceSp = service(providerSp, 60, true);
		for (int day = 1; day <= 7; day++) {
			window(providerSp, day, "08:00", "12:00");
			window(providerSp, day, "14:00", "18:00");
		}
		providerNy = provider(user("prestador-ny@exemplo.test", "PROVIDER", true), "America/New_York", 0);
		serviceNy = service(providerNy, 60, true);
		window(providerNy, 7, "00:00", "06:00");
	}

	UUID user(String email, String role, boolean active) {
		return jdbc.queryForObject(
				"INSERT INTO users (name, email, password_hash, role, active) VALUES ('Fulano de Teste', ?, 'hash-ficticio', ?, ?) RETURNING id",
				UUID.class, email, role, active);
	}

	UUID provider(UUID userId, String zone, int minimumNoticeMinutes) {
		return jdbc.queryForObject(
				"INSERT INTO providers (user_id, business_name, time_zone, minimum_notice_minutes) VALUES (?, 'Negócio de Teste', ?, ?) RETURNING id",
				UUID.class, userId, zone, minimumNoticeMinutes);
	}

	UUID service(UUID providerId, int minutes, boolean active) {
		return jdbc.queryForObject(
				"INSERT INTO services (provider_id, name, duration_minutes, active) VALUES (?, 'Serviço de Teste', ?, ?) RETURNING id",
				UUID.class, providerId, minutes, active);
	}

	void window(UUID providerId, int dayOfWeek, String from, String to) {
		jdbc.update("INSERT INTO availability_windows (provider_id, day_of_week, start_time, end_time) VALUES (?, ?, ?::time, ?::time)",
				providerId, dayOfWeek, from, to);
	}

	long count(String sql, Object... args) {
		return jdbc.queryForObject(sql, Long.class, args);
	}
}
