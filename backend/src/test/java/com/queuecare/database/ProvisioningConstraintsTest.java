package com.queuecare.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Garantias da migration V3 no PostgreSQL real: perfil ADMIN, auditoria somente de acréscimo e nenhum usuário inserido. */
class ProvisioningConstraintsTest extends DatabaseTestBase {

	@Test
	void migrationNaoCriaAdminNemQualquerUsuarioPorConta() throws SQLException {
		var fresh = PostgresTestSupport.newMigratedDatabase();
		try (Connection c = fresh.getConnection(); Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT count(*) FROM users")) {
			rs.next();
			assertEquals(0, rs.getInt(1), "banco recém-migrado não tem nenhum usuário");
		}
	}

	@Test
	void perfilAdminEAceitoEPerfilDesconhecidoNao() throws SQLException {
		try (Connection c = dataSource.getConnection()) {
			PostgresTestSupport.insertUser(c, "admin@exemplo.test", "ADMIN");
			assertSqlState(CHECK_VIOLATION, () -> PostgresTestSupport.insertUser(c, "root@exemplo.test", "ROOT"));
		}
	}

	@Test
	void contaNovaNaoExigeTrocaDeSenhaPorPadrao() throws SQLException {
		try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
			UUID admin = PostgresTestSupport.insertUser(c, "admin@exemplo.test", "ADMIN");
			try (ResultSet rs = st.executeQuery("SELECT must_change_password FROM users WHERE id = '" + admin + "'")) {
				rs.next();
				assertFalse(rs.getBoolean(1));
			}
		}
	}

	private UUID insertAudit(Connection c, UUID actor, String action, String targetRole) throws SQLException {
		try (PreparedStatement ps = c.prepareStatement(
				"INSERT INTO audit_log (actor_user_id, action, target_user_id, target_role) VALUES (?, ?, ?, ?) RETURNING id")) {
			ps.setObject(1, actor);
			ps.setString(2, action);
			ps.setObject(3, actor);
			ps.setString(4, targetRole);
			try (ResultSet rs = ps.executeQuery()) {
				rs.next();
				return (UUID) rs.getObject(1);
			}
		}
	}

	@Test
	void auditoriaAceitaInsercaoEmasRecusaAlteracaoEExclusao() throws SQLException {
		try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
			UUID actor = PostgresTestSupport.insertUser(c, "admin@exemplo.test", "ADMIN");
			UUID id = insertAudit(c, actor, "PROVIDER_ACCOUNT_CREATED", "PROVIDER");

			assertSqlState("23000", () -> st.executeUpdate("UPDATE audit_log SET action = 'ADMIN_BOOTSTRAPPED' WHERE id = '" + id + "'"));
			assertSqlState("23000", () -> st.executeUpdate("UPDATE audit_log SET actor_user_id = actor_user_id"));
			assertSqlState("23000", () -> st.executeUpdate("DELETE FROM audit_log WHERE id = '" + id + "'"));
			assertSqlState("23000", () -> st.executeUpdate("DELETE FROM audit_log"));
			try (ResultSet rs = st.executeQuery("SELECT count(*) FROM audit_log")) {
				rs.next();
				assertEquals(1, rs.getInt(1), "o registro continua lá");
			}
		}
	}

	@Test
	void auditoriaConfereAcaoEPerfilEUsuarioAuditadoNaoPodeSerApagado() throws SQLException {
		try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
			UUID actor = PostgresTestSupport.insertUser(c, "admin@exemplo.test", "ADMIN");

			assertSqlState(CHECK_VIOLATION, () -> insertAudit(c, actor, "ACAO_INVENTADA", "PROVIDER"));
			assertSqlState(CHECK_VIOLATION, () -> insertAudit(c, actor, "PROVIDER_ACCOUNT_CREATED", "ROOT"));
			assertSqlState(FOREIGN_KEY_VIOLATION, () -> insertAudit(c, UUID.randomUUID(), "PROVIDER_ACCOUNT_CREATED", "PROVIDER"));
			insertAudit(c, actor, "ADMIN_BOOTSTRAPPED", "ADMIN");
			assertSqlState(FOREIGN_KEY_VIOLATION, () -> st.executeUpdate("DELETE FROM users WHERE id = '" + actor + "'"));
		}
	}

	@Test
	void tabelaDeAuditoriaNaoTemColunaParaSegredos() throws SQLException {
		List<String> columns = new ArrayList<>();
		try (Connection c = dataSource.getConnection(); Statement st = c.createStatement();
				ResultSet rs = st.executeQuery("SELECT column_name FROM information_schema.columns WHERE table_name = 'audit_log'")) {
			while (rs.next()) {
				columns.add(rs.getString(1));
			}
		}
		assertTrue(columns.containsAll(List.of("id", "occurred_at", "actor_user_id", "action", "target_user_id", "target_role")), columns.toString());
		assertEquals(6, columns.size(), "sem detalhes livres: nada de senha, hash, token ou e-mail: " + columns);
	}
}
