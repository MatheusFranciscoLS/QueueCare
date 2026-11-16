package com.queuecare.database;

import static com.queuecare.database.PostgresTestSupport.count;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Restrições da tabela {@code pets} (V8, ADR 0016) no PostgreSQL real. A aplicação normaliza o texto; aqui se prova que o BANCO
 * recusa, por SQL direto, tudo o que não está na forma normalizada (nenhum caminho alternativo grava dado inválido).
 */
class PetConstraintsTest extends DatabaseTestBase {

	private static final String VALID_NAME = "Rex";

	private UUID insert(Connection c, UUID owner, String name, String species, String breed, String size, String notes) throws SQLException {
		try (PreparedStatement ps = c.prepareStatement(
				"INSERT INTO pets (customer_id, name, species, breed, size, notes) VALUES (?, ?, ?, ?, ?, ?) RETURNING id")) {
			ps.setObject(1, owner);
			ps.setString(2, name);
			ps.setString(3, species);
			ps.setString(4, breed);
			ps.setString(5, size);
			ps.setString(6, notes);
			try (var rs = ps.executeQuery()) {
				rs.next();
				return rs.getObject(1, UUID.class);
			}
		}
	}

	private void rejectedByCheck(String name, String species, String breed, String size, String notes) throws SQLException {
		try (Connection c = dataSource.getConnection()) {
			assertSqlState(CHECK_VIOLATION, () -> insert(c, customer, name, species, breed, size, notes));
		}
	}

	/** Além do limite, quem recusa é o próprio tipo da coluna (varchar(n), SQLSTATE 22001), que conta pontos de código. */
	private void rejectedByLength(String name, String species, String breed, String size, String notes) throws SQLException {
		try (Connection c = dataSource.getConnection()) {
			assertSqlState("22001", () -> insert(c, customer, name, species, breed, size, notes));
		}
	}

	private void accepted(String name, String species, String breed, String size, String notes) throws SQLException {
		try (Connection c = dataSource.getConnection()) {
			insert(c, customer, name, species, breed, size, notes);
		}
	}

	@Test
	void migrationV8CriaATabelaEAChaveParaOVinculoComAgendamento() throws SQLException {
		try (Connection c = dataSource.getConnection()) {
			assertEquals(1, count(c, "SELECT count(*) FROM flyway_schema_history WHERE version = '8' AND success"));
			assertEquals(1, count(c, "SELECT count(*) FROM information_schema.tables WHERE table_schema = 'public' AND table_name = 'pets'"));
			// A chave composta (id, customer_id) é o alvo da chave estrangeira de appointments (PR seguinte).
			assertEquals(1, count(c, "SELECT count(*) FROM pg_constraint WHERE conname = 'pets_id_customer_uk' AND contype = 'u'"));
		}
	}

	@Test
	void petValidoEhAceitoComEPadroesDeColunas() throws SQLException {
		accepted(VALID_NAME, "DOG", null, null, null);
		accepted("Mel Maria", "CAT", "Siamês", "SMALL", "Tem medo de secador.\nNão gosta de cheiro forte.");
		try (Connection c = dataSource.getConnection()) {
			assertEquals(2, count(c, "SELECT count(*) FROM pets WHERE active AND created_at IS NOT NULL AND updated_at IS NOT NULL"));
		}
	}

	@Test
	void nomeForaDaFormaNormalizadaEhRecusado() throws SQLException {
		rejectedByCheck("", "DOG", null, null, null);
		rejectedByCheck(" Rex", "DOG", null, null, null);
		rejectedByCheck("Rex ", "DOG", null, null, null);
		rejectedByCheck("Rex  Jr", "DOG", null, null, null); // espaço duplo
		rejectedByCheck("Rex\tJr", "DOG", null, null, null);
		rejectedByCheck("Rex\nJr", "DOG", null, null, null);
		rejectedByCheck("Rex\rJr", "DOG", null, null, null);
		rejectedByCheck("Rex\u0007", "DOG", null, null, null); // sino (C0)
		rejectedByCheck("Rex\u007F", "DOG", null, null, null); // DEL
		rejectedByCheck("Rex\u0085", "DOG", null, null, null); // NEL (C1)
		rejectedByLength("x".repeat(81), "DOG", null, null, null);
		accepted("x".repeat(80), "DOG", null, null, null);
		accepted("Ça va 🐶", "DOG", null, null, null); // acentos e emoji (1 ponto de código) são aceitos
	}

	@Test
	void tamanhoDoNomeContaPontosDeCodigoComoOChar_length() throws SQLException {
		// 80 emojis = 80 pontos de código (160 unidades UTF-16): cabe; 81 não.
		accepted("🐶".repeat(80), "DOG", null, null, null);
		rejectedByLength("🐶".repeat(81), "DOG", null, null, null);
	}

	@Test
	void campoObrigatorioNaoAceitaNulo() throws SQLException {
		try (Connection c = dataSource.getConnection()) {
			assertSqlState(NOT_NULL_VIOLATION, () -> insert(c, customer, null, "DOG", null, null, null));
			assertSqlState(NOT_NULL_VIOLATION, () -> insert(c, customer, VALID_NAME, null, null, null, null));
			assertSqlState(NOT_NULL_VIOLATION, () -> insert(c, null, VALID_NAME, "DOG", null, null, null));
		}
	}

	@Test
	void especieEPorteForaDaListaSaoRecusados() throws SQLException {
		rejectedByCheck(VALID_NAME, "BIRD", null, null, null);
		rejectedByCheck(VALID_NAME, "dog", null, null, null);
		rejectedByCheck(VALID_NAME, "", null, null, null);
		rejectedByCheck(VALID_NAME, "DOG", null, "HUGE", null);
		rejectedByCheck(VALID_NAME, "DOG", null, "small", null);
		rejectedByCheck(VALID_NAME, "DOG", null, "", null);
		accepted(VALID_NAME, "DOG", null, "LARGE", null);
	}

	@Test
	void racaForaDaFormaNormalizadaEhRecusada() throws SQLException {
		rejectedByCheck(VALID_NAME, "DOG", "", null, null); // vazio vira nulo na aplicação; no banco é recusado
		rejectedByCheck(VALID_NAME, "DOG", " Poodle", null, null);
		rejectedByCheck(VALID_NAME, "DOG", "Poodle ", null, null);
		rejectedByCheck(VALID_NAME, "DOG", "Pastor  Alemão", null, null);
		rejectedByCheck(VALID_NAME, "DOG", "Poodle\nToy", null, null);
		rejectedByCheck(VALID_NAME, "DOG", "Poodle\u0085", null, null);
		rejectedByLength(VALID_NAME, "DOG", "x".repeat(81), null, null);
		accepted(VALID_NAME, "DOG", "Pastor Alemão", null, null);
	}

	@Test
	void observacoesForaDaFormaNormalizadaSaoRecusadas() throws SQLException {
		rejectedByCheck(VALID_NAME, "DOG", null, null, ""); // vazio vira nulo na aplicação
		rejectedByCheck(VALID_NAME, "DOG", null, null, " texto");
		rejectedByCheck(VALID_NAME, "DOG", null, null, "texto ");
		rejectedByCheck(VALID_NAME, "DOG", null, null, "\ntexto"); // quebra de linha no começo
		rejectedByCheck(VALID_NAME, "DOG", null, null, "texto\n"); // e no fim
		rejectedByCheck(VALID_NAME, "DOG", null, null, "texto \n"); // espaço e quebra no fim
		rejectedByCheck(VALID_NAME, "DOG", null, null, "linha 1\r\nlinha 2"); // CRLF: o \r é controle
		rejectedByCheck(VALID_NAME, "DOG", null, null, "linha 1\rlinha 2");
		rejectedByCheck(VALID_NAME, "DOG", null, null, "com\ttab");
		rejectedByCheck(VALID_NAME, "DOG", null, null, "sino\u0007");
		rejectedByCheck(VALID_NAME, "DOG", null, null, "del\u007F");
		rejectedByCheck(VALID_NAME, "DOG", null, null, "nel\u0085");
		rejectedByLength(VALID_NAME, "DOG", null, null, "x".repeat(501));
		accepted(VALID_NAME, "DOG", null, null, "x".repeat(500));
		accepted(VALID_NAME, "DOG", null, null, "linha 1\nlinha 2\n\nlinha 4"); // só \n entre os controles
	}

	@Test
	void donoDoPetPrecisaExistirENaoPodeSerApagadoComPets() throws SQLException {
		try (Connection c = dataSource.getConnection()) {
			assertSqlState(FOREIGN_KEY_VIOLATION, () -> insert(c, UUID.randomUUID(), VALID_NAME, "DOG", null, null, null));
			insert(c, customer, VALID_NAME, "DOG", null, null, null);
			try (PreparedStatement ps = c.prepareStatement("DELETE FROM users WHERE id = ?")) {
				ps.setObject(1, customer);
				assertSqlState(FOREIGN_KEY_VIOLATION, ps::executeUpdate);
			}
		}
	}
}
