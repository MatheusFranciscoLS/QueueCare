package com.queuecare.database;

import static com.queuecare.database.PostgresTestSupport.count;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/**
 * V11 (ADR 0017): o evento RESCHEDULED pode repetir, as demais transições continuam no máximo uma vez por agendamento (a fila depende
 * disso para CHECKED_IN), e o evento de remarcação guarda o horário anterior e o novo, diferentes entre si. PostgreSQL real, por SQL direto.
 */
class RescheduleEventConstraintsTest extends DatabaseTestBase {

	private UUID appointment(Connection c) throws SQLException {
		return PostgresTestSupport.insertAppointment(c, provider1, service1, customer, at("10:00"), at("11:00"), "SCHEDULED");
	}

	private void event(Connection c, UUID appointment, String type, String previous, String next) throws SQLException {
		try (PreparedStatement ps = c.prepareStatement("INSERT INTO appointment_events (appointment_id, actor_id, event_type, previous_starts_at, new_starts_at) VALUES (?, ?, ?, ?::timestamptz, ?::timestamptz)")) {
			ps.setObject(1, appointment);
			ps.setObject(2, customer);
			ps.setString(3, type);
			ps.setString(4, previous);
			ps.setString(5, next);
			ps.executeUpdate();
		}
	}

	@Test
	void migrationV11Aplicada() throws SQLException {
		try (Connection c = dataSource.getConnection()) {
			assertEquals(1, count(c, "SELECT count(*) FROM flyway_schema_history WHERE version = '11' AND success"));
			assertEquals(2, count(c, "SELECT count(*) FROM information_schema.columns WHERE table_name = 'appointment_events' AND column_name IN ('previous_starts_at','new_starts_at')"));
		}
	}

	@Test
	void remarcacaoPodeRepetirMasOsOutrosTiposContinuamNoMaximoUmaVez() throws SQLException {
		try (Connection c = dataSource.getConnection()) {
			UUID id = appointment(c);
			event(c, id, "RESCHEDULED", "2026-11-17T10:00:00Z", "2026-11-18T10:00:00Z");
			event(c, id, "RESCHEDULED", "2026-11-18T10:00:00Z", "2026-11-19T10:00:00Z");
			event(c, id, "RESCHEDULED", "2026-11-19T10:00:00Z", "2026-11-17T10:00:00Z"); // pode até voltar ao horário original
			assertEquals(3, count(c, "SELECT count(*) FROM appointment_events WHERE event_type = 'RESCHEDULED'"));

			for (String type : new String[] { "SCHEDULED", "CHECKED_IN", "COMPLETED", "CANCELLED" }) {
				event(c, id, type, null, null);
				assertSqlState(UNIQUE_VIOLATION, () -> event(c, id, type, null, null));
			}
		}
	}

	@Test
	void eventoDeRemarcacaoExigeOsDoisHorariosDiferentesEOsOutrosTiposNaoOsTem() throws SQLException {
		try (Connection c = dataSource.getConnection()) {
			UUID id = appointment(c);
			assertSqlState(CHECK_VIOLATION, () -> event(c, id, "RESCHEDULED", null, null));
			assertSqlState(CHECK_VIOLATION, () -> event(c, id, "RESCHEDULED", "2026-11-17T10:00:00Z", null));
			assertSqlState(CHECK_VIOLATION, () -> event(c, id, "RESCHEDULED", null, "2026-11-18T10:00:00Z"));
			assertSqlState(CHECK_VIOLATION, () -> event(c, id, "RESCHEDULED", "2026-11-17T10:00:00Z", "2026-11-17T10:00:00Z")); // remarcar para o mesmo horário
			assertSqlState(CHECK_VIOLATION, () -> event(c, id, "CANCELLED", "2026-11-17T10:00:00Z", "2026-11-18T10:00:00Z"));
			assertSqlState(CHECK_VIOLATION, () -> event(c, id, "REMARCADO", null, null)); // tipo desconhecido
			assertEquals(0, count(c, "SELECT count(*) FROM appointment_events"));
		}
	}

	@Test
	void migrationSobreBancoComEventosAntigosPreservaARegraDeUmEventoPorTipo() throws SQLException {
		DataSource old = PostgresTestSupport.newDatabaseMigratedTo("10");
		UUID appointmentId;
		try (Connection c = old.getConnection()) {
			UUID owner = PostgresTestSupport.insertUser(c, "prestador-antigo@exemplo.test", "PROVIDER");
			UUID tutor = PostgresTestSupport.insertUser(c, "tutor-antigo@exemplo.test", "CUSTOMER");
			UUID provider = PostgresTestSupport.insertProvider(c, owner);
			UUID service = PostgresTestSupport.insertService(c, provider, 60);
			appointmentId = PostgresTestSupport.insertAppointment(c, provider, service, tutor, at("10:00"), at("11:00"), "CHECKED_IN");
			try (Statement st = c.createStatement()) {
				st.execute("INSERT INTO appointment_events (appointment_id, actor_id, event_type) VALUES ('" + appointmentId + "', '" + tutor + "', 'SCHEDULED')");
				st.execute("INSERT INTO appointment_events (appointment_id, actor_id, event_type) VALUES ('" + appointmentId + "', '" + owner + "', 'CHECKED_IN')");
			}
		}

		PostgresTestSupport.migrateToLatest(old); // a V11 roda sobre eventos que já existiam

		try (Connection c = old.getConnection(); Statement st = c.createStatement()) {
			assertEquals(2, count(c, "SELECT count(*) FROM appointment_events WHERE previous_starts_at IS NULL AND new_starts_at IS NULL"));
			// o evento de chegada continua único por agendamento (a fila depende disso)
			assertSqlState(UNIQUE_VIOLATION, () -> st.execute("INSERT INTO appointment_events (appointment_id, event_type) VALUES ('" + appointmentId + "', 'CHECKED_IN')"));
			// e agora existe a remarcação
			st.execute("INSERT INTO appointment_events (appointment_id, event_type, previous_starts_at, new_starts_at) VALUES ('" + appointmentId
					+ "', 'RESCHEDULED', '2026-11-17T10:00:00Z', '2026-11-18T10:00:00Z')");
		}
	}
}
