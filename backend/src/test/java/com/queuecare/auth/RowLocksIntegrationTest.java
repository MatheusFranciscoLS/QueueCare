package com.queuecare.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Fixa o MODO do bloqueio de usuário: {@code FOR NO KEY UPDATE} não conflita com o {@code FOR KEY SHARE} que a gravação da
 * auditoria (FKs para ator e alvo) toma nas linhas de usuário. Se uma troca de versão do Hibernate/dialeto passasse a gerar
 * {@code FOR UPDATE}, este teste falharia (a gravação da auditoria esperaria o bloqueio) em vez de o deadlock só aparecer em produção.
 */
class RowLocksIntegrationTest extends AuthIntegrationTestBase {

	@Autowired
	RowLocks locks;

	@Autowired
	TransactionTemplate transaction;

	@Autowired
	DataSource dataSource;

	@Test
	void bloqueioDeUsuarioNaoImpedeAGravacaoDeAuditoriaQueReferenciaAquelaLinha() throws Exception {
		UUID actor = seedUser("admin@exemplo.test", "ADMIN", true);
		UUID target = seedUser("c@exemplo.test", "CUSTOMER", true);

		long waitedMillis = transaction.execute(status -> {
			locks.lockUser(actor).orElseThrow();
			locks.lockUser(target).orElseThrow();
			// Outra conexão grava uma auditoria que referencia as DUAS linhas bloqueadas (FK = FOR KEY SHARE nelas).
			long start = System.nanoTime();
			try (Connection other = dataSource.getConnection()) {
				try (var st = other.createStatement()) {
					st.execute("SET lock_timeout = '1500ms'");
				}
				try (PreparedStatement ps = other.prepareStatement(
						"INSERT INTO audit_log (actor_user_id, action, target_user_id, target_role) VALUES (?, 'USER_DEACTIVATED', ?, 'CUSTOMER')")) {
					ps.setObject(1, actor);
					ps.setObject(2, target);
					ps.executeUpdate();
				}
			} catch (java.sql.SQLException e) {
				throw new IllegalStateException("a auditoria ficou esperando o bloqueio do usuário: " + e.getMessage(), e);
			}
			return (System.nanoTime() - start) / 1_000_000;
		});

		assertTrue(waitedMillis < 1_000, "sem espera: " + waitedMillis + " ms");
		assertEquals(1L, count("SELECT count(*) FROM audit_log"));
	}
}
