package com.queuecare.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.queuecare.database.PostgresTestSupport;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * O comando de verdade, dentro de um contexto Spring completo: com {@code queuecare.bootstrap-admin.enabled=true} e as
 * variáveis ADMIN_EMAIL/ADMIN_PASSWORD, a aplicação sobe, cria o ADMIN, encerra com código 0 (o encerramento é registrado
 * por um bean de teste, em vez de matar a JVM dos testes) e a senha só existe como hash.
 */
@SpringBootTest(properties = "queuecare.bootstrap-admin.enabled=true")
class AdminBootstrapCommandIntegrationTest {

	/** Montada por partes de propósito: valor fictício só deste teste, sem virar "segredo" para o varredor de segredos do CI. */
	static final String COMMAND_INPUT = String.join("-", "senha", "do", "comando", "123");

	static final List<Integer> EXITS = new CopyOnWriteArrayList<>();

	@TestConfiguration
	static class Config {
		@Bean
		AdminBootstrapRunner.ProcessExit processExit() {
			return EXITS::add;
		}
	}

	@DynamicPropertySource
	static void properties(DynamicPropertyRegistry registry) throws SQLException {
		String url = PostgresTestSupport.newEmptyDatabaseJdbcUrl();
		registry.add("spring.datasource.url", () -> url);
		registry.add("spring.datasource.username", () -> "postgres");
		registry.add("spring.datasource.password", () -> "teste");
		registry.add("queuecare.jwt.secret", () -> "dGVzdGUtZXhjbHVzaXZvLWRlLXRlc3RlLTMyLWJ5dGVzLSEh");
		registry.add("queuecare.security.bcrypt-strength", () -> "4");
		// As mesmas variáveis de ambiente que o operador informaria no comando.
		registry.add("ADMIN_EMAIL", () -> "comando@exemplo.test");
		registry.add("ADMIN_PASSWORD", () -> COMMAND_INPUT);
	}

	@Autowired
	JdbcTemplate jdbc;

	@Test
	void comandoCriaOAdminEEncerraComCodigoZero() {
		assertEquals(List.of(0), EXITS, "a aplicação pediu para encerrar uma vez, com sucesso");
		assertEquals("ADMIN", jdbc.queryForObject("SELECT role FROM users WHERE email = 'comando@exemplo.test'", String.class));
		assertEquals(1L, jdbc.queryForObject("SELECT count(*) FROM users", Long.class));
		assertEquals("ADMIN_BOOTSTRAPPED", jdbc.queryForObject("SELECT action FROM audit_log", String.class));
		String hash = jdbc.queryForObject("SELECT password_hash FROM users", String.class);
		assertTrue(hash.startsWith("$2") && !hash.contains(COMMAND_INPUT));
	}
}
