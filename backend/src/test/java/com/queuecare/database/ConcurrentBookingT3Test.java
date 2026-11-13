package com.queuecare.database;

import static com.queuecare.database.PostgresTestSupport.count;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * Teste T3 (regra R2, ADR 0002): reservas SIMULTÂNEAS do mesmo horário, com PostgreSQL real.
 * Cada tentativa usa sua própria conexão e sua própria thread, sem nenhum @Transactional envolvendo o teste
 * (isso esconderia o comportamento real entre transações). A aplicação "checar e depois gravar" não protegeria;
 * quem decide é a restrição de exclusão do banco.
 */
class ConcurrentBookingT3Test extends DatabaseTestBase {

	/** Resultado de uma tentativa: sucesso (sqlState nulo) ou o SQLSTATE devolvido pelo banco. */
	private record Attempt(String sqlState) {
		boolean won() {
			return sqlState == null;
		}
	}

	private List<Attempt> race(int contenders, Instant start, Instant end, boolean explicitTransaction) throws Exception {
		ExecutorService pool = Executors.newFixedThreadPool(contenders);
		CyclicBarrier startTogether = new CyclicBarrier(contenders);
		try {
			List<Callable<Attempt>> tasks = new ArrayList<>();
			for (int i = 0; i < contenders; i++) {
				tasks.add(() -> {
					try (Connection c = dataSource.getConnection()) {
						c.setAutoCommit(!explicitTransaction);
						startTogether.await(10, TimeUnit.SECONDS);
						try {
							PostgresTestSupport.insertAppointment(c, provider1, service1, customer, start, end, "SCHEDULED");
							if (explicitTransaction) {
								c.commit();
							}
							return new Attempt(null);
						} catch (SQLException e) {
							if (explicitTransaction) {
								c.rollback();
							}
							return new Attempt(e.getSQLState());
						}
					}
				});
			}
			List<Attempt> attempts = new ArrayList<>();
			for (Future<Attempt> future : pool.invokeAll(tasks, 60, TimeUnit.SECONDS)) {
				attempts.add(future.get());
			}
			return attempts;
		} finally {
			pool.shutdownNow();
		}
	}

	private void assertOneWinner(List<Attempt> attempts, int contenders) throws SQLException {
		long winners = attempts.stream().filter(Attempt::won).count();
		List<String> losers = attempts.stream().filter(a -> !a.won()).map(Attempt::sqlState).toList();
		assertEquals(1, winners, "exatamente uma reserva deve vencer: " + attempts);
		assertEquals(contenders - 1, losers.size());
		assertTrue(losers.stream().allMatch(EXCLUSION_VIOLATION::equals),
				"as perdedoras devem receber exclusion_violation (23P01), e não outro erro: " + losers);
	}

	@Test
	void duasReservasSimultaneasDoMesmoHorarioUmaVenceAOutraRecebeConflito() throws Exception {
		for (int round = 0; round < 20; round++) {
			Instant start = at("08:00").plusSeconds(round * 7200L);
			List<Attempt> attempts = race(2, start, start.plusSeconds(3600), false);

			assertOneWinner(attempts, 2);
		}
		try (Connection c = dataSource.getConnection()) {
			assertEquals(20, count(c, "SELECT count(*) FROM appointments WHERE status <> 'CANCELLED'"),
					"uma reserva ativa por rodada");
		}
	}

	@Test
	void muitasReservasSimultaneasApenasUmaVence() throws Exception {
		List<Attempt> attempts = race(8, at("10:00"), at("11:00"), false);

		assertOneWinner(attempts, 8);
		try (Connection c = dataSource.getConnection()) {
			assertEquals(1, count(c, "SELECT count(*) FROM appointments"));
		}
	}

	@Test
	void reservasSimultaneasComIntervalosParcialmenteSobrepostosApenasUmaVence() throws Exception {
		ExecutorService pool = Executors.newFixedThreadPool(2);
		CyclicBarrier startTogether = new CyclicBarrier(2);
		try {
			Instant[][] intervals = {{at("10:00"), at("11:00")}, {at("10:30"), at("11:30")}};
			List<Future<Attempt>> futures = new ArrayList<>();
			for (Instant[] interval : intervals) {
				futures.add(pool.submit(() -> {
					try (Connection c = dataSource.getConnection()) {
						startTogether.await(10, TimeUnit.SECONDS);
						try {
							PostgresTestSupport.insertAppointment(c, provider1, service1, customer, interval[0], interval[1], "SCHEDULED");
							return new Attempt(null);
						} catch (SQLException e) {
							return new Attempt(e.getSQLState());
						}
					}
				}));
			}
			List<Attempt> attempts = new ArrayList<>();
			for (Future<Attempt> future : futures) {
				attempts.add(future.get(60, TimeUnit.SECONDS));
			}
			assertOneWinner(attempts, 2);
		} finally {
			pool.shutdownNow();
		}
		try (Connection c = dataSource.getConnection()) {
			assertEquals(1, count(c, "SELECT count(*) FROM appointments"));
		}
	}

	@Test
	void comTransacoesExplicitasOSegundoEsperaOPrimeiroConfirmarEDepoisFalha() throws Exception {
		for (int round = 0; round < 10; round++) {
			Instant start = at("08:00").plusSeconds(round * 7200L);
			List<Attempt> attempts = race(2, start, start.plusSeconds(3600), true);

			assertOneWinner(attempts, 2);
		}
		try (Connection c = dataSource.getConnection()) {
			assertEquals(10, count(c, "SELECT count(*) FROM appointments WHERE status <> 'CANCELLED'"));
		}
	}

	@Test
	void reservasSimultaneasDeHorariosDiferentesNaoSeAtrapalham() throws Exception {
		ExecutorService pool = Executors.newFixedThreadPool(4);
		CyclicBarrier startTogether = new CyclicBarrier(4);
		try {
			List<Future<Attempt>> futures = new ArrayList<>();
			for (int i = 0; i < 4; i++) {
				Instant start = at("08:00").plusSeconds(i * 3600L);
				futures.add(pool.submit(() -> {
					try (Connection c = dataSource.getConnection()) {
						startTogether.await(10, TimeUnit.SECONDS);
						try {
							PostgresTestSupport.insertAppointment(c, provider1, service1, customer, start, start.plusSeconds(3600), "SCHEDULED");
							return new Attempt(null);
						} catch (SQLException e) {
							return new Attempt(e.getSQLState());
						}
					}
				}));
			}
			for (Future<Attempt> future : futures) {
				assertTrue(future.get(60, TimeUnit.SECONDS).won());
			}
		} finally {
			pool.shutdownNow();
		}
		try (Connection c = dataSource.getConnection()) {
			assertEquals(4, count(c, "SELECT count(*) FROM appointments"));
		}
	}

}
