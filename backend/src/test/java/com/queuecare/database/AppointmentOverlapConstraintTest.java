package com.queuecare.database;

import static com.queuecare.database.PostgresTestSupport.count;
import static com.queuecare.database.PostgresTestSupport.insertAppointment;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Prova da restrição de exclusão do ADR 0002 (regra R2) no PostgreSQL real.
 * Cenários de docs/cenarios-r2.md. Existente: 60 min às 10:00, [10:00, 11:00), prestador 1, ativo.
 */
class AppointmentOverlapConstraintTest extends DatabaseTestBase {

	private UUID existing;

	private void createExisting(Connection c, String status) throws SQLException {
		existing = insertAppointment(c, provider1, service1, customer, at("10:00"), at("11:00"), status);
	}

	private void book(Connection c, UUID provider, UUID service, String from, String to, String status) throws SQLException {
		insertAppointment(c, provider, service, customer, at(from), at(to), status);
	}

	@Test
	void usaPostgreSql16DeVerdade() throws SQLException {
		try (Connection c = dataSource.getConnection()) {
			String version = PostgresTestSupport.scalar(c, "SHOW server_version");
			assertNotNull(version);
			org.junit.jupiter.api.Assertions.assertTrue(version.startsWith("16."), "versão do PostgreSQL: " + version);
			assertEquals(1, count(c, "SELECT count(*) FROM pg_extension WHERE extname = 'btree_gist'"));
		}
	}

	@Test
	void cenarioA_intervaloDentroDoExistenteEhRecusado() throws SQLException {
		try (Connection c = dataSource.getConnection()) {
			createExisting(c, "SCHEDULED");
			assertSqlState(EXCLUSION_VIOLATION, () -> book(c, provider1, service1, "10:30", "11:00", "SCHEDULED"));
		}
	}

	@Test
	void cenarioB_consecutivosSaoAceitosNosDoisLados() throws SQLException {
		try (Connection c = dataSource.getConnection()) {
			createExisting(c, "SCHEDULED");
			book(c, provider1, service1, "11:00", "12:00", "SCHEDULED");
			book(c, provider1, service1, "09:00", "10:00", "SCHEDULED");
			assertEquals(3, count(c, "SELECT count(*) FROM appointments"));
		}
	}

	@Test
	void cenarioC_sobreposicaoParcialNoInicioEhRecusada() throws SQLException {
		try (Connection c = dataSource.getConnection()) {
			createExisting(c, "SCHEDULED");
			assertSqlState(EXCLUSION_VIOLATION, () -> book(c, provider1, service1, "09:45", "10:15", "SCHEDULED"));
		}
	}

	@Test
	void sobreposicaoParcialNoFimEhRecusada() throws SQLException {
		try (Connection c = dataSource.getConnection()) {
			createExisting(c, "SCHEDULED");
			assertSqlState(EXCLUSION_VIOLATION, () -> book(c, provider1, service1, "10:45", "11:30", "SCHEDULED"));
		}
	}

	@Test
	void cenarioD_existenteCanceladoLiberaOHorario() throws SQLException {
		try (Connection c = dataSource.getConnection()) {
			createExisting(c, "CANCELLED");
			book(c, provider1, service1, "10:00", "11:00", "SCHEDULED");
			assertEquals(1, count(c, "SELECT count(*) FROM appointments WHERE status <> 'CANCELLED'"));
		}
	}

	@Test
	void cenarioE_outroPrestadorTemOutraAgenda() throws SQLException {
		try (Connection c = dataSource.getConnection()) {
			createExisting(c, "SCHEDULED");
			book(c, provider2, service2, "10:00", "11:00", "SCHEDULED");
			assertEquals(2, count(c, "SELECT count(*) FROM appointments"));
		}
	}

	@Test
	void cenarioF_intervaloIgualEhRecusado() throws SQLException {
		try (Connection c = dataSource.getConnection()) {
			createExisting(c, "SCHEDULED");
			assertSqlState(EXCLUSION_VIOLATION, () -> book(c, provider1, service1, "10:00", "11:00", "SCHEDULED"));
		}
	}

	@Test
	void cenarioG_novoQueEnvolveOExistenteEhRecusado() throws SQLException {
		try (Connection c = dataSource.getConnection()) {
			createExisting(c, "SCHEDULED");
			assertSqlState(EXCLUSION_VIOLATION, () -> book(c, provider1, service1, "09:30", "11:30", "SCHEDULED"));
		}
	}

	@Test
	void cenarioK_cancelarEReservarDeNovoNoMesmoHorario() throws SQLException {
		try (Connection c = dataSource.getConnection()) {
			createExisting(c, "SCHEDULED");
			try (PreparedStatement ps = c.prepareStatement("UPDATE appointments SET status = 'CANCELLED' WHERE id = ?")) {
				ps.setObject(1, existing);
				ps.executeUpdate();
			}
			book(c, provider1, service1, "10:00", "11:00", "SCHEDULED");
			assertEquals(1, count(c, "SELECT count(*) FROM appointments WHERE status <> 'CANCELLED'"));
		}
	}

	@Test
	void reativarUmCanceladoQueAgoraColideEhRecusado() throws SQLException {
		try (Connection c = dataSource.getConnection()) {
			createExisting(c, "CANCELLED");
			book(c, provider1, service1, "10:00", "11:00", "SCHEDULED");
			assertSqlState(EXCLUSION_VIOLATION, () -> {
				try (PreparedStatement ps = c.prepareStatement("UPDATE appointments SET status = 'SCHEDULED' WHERE id = ?")) {
					ps.setObject(1, existing);
					ps.executeUpdate();
				}
			});
		}
	}

	@Test
	void atualizarOIntervaloParaUmConflitoEhRecusado() throws SQLException {
		try (Connection c = dataSource.getConnection()) {
			createExisting(c, "SCHEDULED");
			book(c, provider1, service1, "12:00", "13:00", "SCHEDULED");
			assertSqlState(EXCLUSION_VIOLATION, () -> {
				try (PreparedStatement ps = c.prepareStatement(
						"UPDATE appointments SET starts_at = ?, ends_at = ? WHERE starts_at = ?")) {
					ps.setObject(1, OffsetDateTime.ofInstant(at("10:30"), ZoneOffset.UTC));
					ps.setObject(2, OffsetDateTime.ofInstant(at("11:30"), ZoneOffset.UTC));
					ps.setObject(3, OffsetDateTime.ofInstant(at("12:00"), ZoneOffset.UTC));
					ps.executeUpdate();
				}
			});
		}
	}

	@Test
	void moverParaOutroPrestadorQueTemConflitoEhRecusado() throws SQLException {
		try (Connection c = dataSource.getConnection()) {
			createExisting(c, "SCHEDULED");
			insertAppointment(c, provider2, service2, customer, at("10:00"), at("11:00"), "SCHEDULED");
			// Move o agendamento do prestador 1 para o prestador 2 (e o serviço dele): colide com o que já existe lá.
			assertSqlState(EXCLUSION_VIOLATION, () -> {
				try (PreparedStatement ps = c.prepareStatement("UPDATE appointments SET provider_id = ?, service_id = ? WHERE id = ?")) {
					ps.setObject(1, provider2);
					ps.setObject(2, service2);
					ps.setObject(3, existing);
					ps.executeUpdate();
				}
			});
		}
	}

	@Test
	void statusNuloNaoEscapaDaProtecao() throws SQLException {
		try (Connection c = dataSource.getConnection()) {
			assertSqlState(NOT_NULL_VIOLATION, () -> {
				try (PreparedStatement ps = c.prepareStatement(
						"INSERT INTO appointments (provider_id, service_id, customer_id, starts_at, ends_at, status) VALUES (?, ?, ?, ?, ?, NULL)")) {
					ps.setObject(1, provider1);
					ps.setObject(2, service1);
					ps.setObject(3, customer);
					ps.setObject(4, OffsetDateTime.ofInstant(at("10:00"), ZoneOffset.UTC));
					ps.setObject(5, OffsetDateTime.ofInstant(at("11:00"), ZoneOffset.UTC));
					ps.executeUpdate();
				}
			});
		}
	}

	@Test
	void duracaoZeroOuNegativaEhRecusada() throws SQLException {
		try (Connection c = dataSource.getConnection()) {
			assertSqlState(CHECK_VIOLATION, () -> book(c, provider1, service1, "10:00", "10:00", "SCHEDULED"));
			assertSqlState(CHECK_VIOLATION, () -> book(c, provider1, service1, "11:00", "10:00", "SCHEDULED"));
		}
	}

	@Test
	void statusInvalidoEhRecusado() throws SQLException {
		try (Connection c = dataSource.getConnection()) {
			assertSqlState(CHECK_VIOLATION, () -> book(c, provider1, service1, "10:00", "11:00", "QUALQUER_COISA"));
		}
	}

	@Test
	void servicoDeOutroPrestadorNoAgendamentoEhRecusado() throws SQLException {
		try (Connection c = dataSource.getConnection()) {
			// Agendamento do prestador 1 usando o serviço do prestador 2: a FK composta recusa.
			assertSqlState(FOREIGN_KEY_VIOLATION, () -> book(c, provider1, service2, "10:00", "11:00", "SCHEDULED"));
		}
	}

	@Test
	void naoApagaPrestadorUsuarioOuServicoComHistorico() throws SQLException {
		try (Connection c = dataSource.getConnection()) {
			createExisting(c, "SCHEDULED");
			assertSqlState(FOREIGN_KEY_VIOLATION, () -> c.createStatement().executeUpdate("DELETE FROM services WHERE id = '" + service1 + "'"));
			assertSqlState(FOREIGN_KEY_VIOLATION, () -> c.createStatement().executeUpdate("DELETE FROM providers WHERE id = '" + provider1 + "'"));
			assertSqlState(FOREIGN_KEY_VIOLATION, () -> c.createStatement().executeUpdate("DELETE FROM users WHERE id = '" + customer + "'"));
		}
	}

	@Test
	void eventosDoAgendamentoSaoRegistradosEProtegidos() throws SQLException {
		try (Connection c = dataSource.getConnection()) {
			createExisting(c, "SCHEDULED");
			try (PreparedStatement ps = c.prepareStatement(
					"INSERT INTO appointment_events (appointment_id, actor_id, event_type) VALUES (?, ?, 'SCHEDULED')")) {
				ps.setObject(1, existing);
				ps.setObject(2, customer);
				ps.executeUpdate();
			}
			// Evento do sistema, sem usuário humano (actor_id nulo).
			c.createStatement().executeUpdate("INSERT INTO appointment_events (appointment_id, event_type) VALUES ('" + existing + "', 'CANCELLED')");
			assertEquals(2, count(c, "SELECT count(*) FROM appointment_events"));
			assertSqlState(CHECK_VIOLATION, () -> c.createStatement()
					.executeUpdate("INSERT INTO appointment_events (appointment_id, event_type) VALUES ('" + existing + "', 'INVENTADO')"));
			assertSqlState(FOREIGN_KEY_VIOLATION, () -> c.createStatement().executeUpdate("DELETE FROM appointments WHERE id = '" + existing + "'"));
		}
	}
}
