package com.queuecare.scheduling.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Agendamento CONCORRENTE pelo serviço (Spring completo, PostgreSQL real): várias threads pedem o mesmo horário ao
 * mesmo tempo. Exatamente uma vence; as demais recebem {@link BookingConflictException} (nunca outro erro), e o banco
 * fica com uma única reserva ativa. Cobre tanto a checagem prévia quanto a tradução do erro do banco (23P01 e 40P01).
 */
class BookingConcurrencyIntegrationTest extends BookingIntegrationTestBase {

	@Autowired
	BookingService bookingService;

	/** Resultado de uma tentativa: sucesso ou o tipo de exceção recebida. */
	private record Outcome(Class<? extends Throwable> error) {
		boolean won() {
			return error == null;
		}
	}

	private List<Outcome> race(int contenders, BookingRequest request) throws Exception {
		ExecutorService pool = Executors.newFixedThreadPool(contenders);
		CyclicBarrier startTogether = new CyclicBarrier(contenders);
		try {
			List<Callable<Outcome>> tasks = new ArrayList<>();
			for (int i = 0; i < contenders; i++) {
				tasks.add(() -> {
					startTogether.await(10, TimeUnit.SECONDS);
					try {
						bookingService.book(request);
						return new Outcome(null);
					} catch (Throwable t) {
						return new Outcome(t.getClass());
					}
				});
			}
			List<Outcome> outcomes = new ArrayList<>();
			for (Future<Outcome> future : pool.invokeAll(tasks, 60, TimeUnit.SECONDS)) {
				assertFalse(future.isCancelled(), "uma tentativa não terminou no prazo");
				outcomes.add(future.get());
			}
			assertEquals(contenders, outcomes.size());
			return outcomes;
		} finally {
			pool.shutdownNow();
		}
	}

	private void assertOneWinnerOthersConflict(List<Outcome> outcomes) {
		assertEquals(1, outcomes.stream().filter(Outcome::won).count(), "exatamente uma vence: " + outcomes);
		assertTrue(outcomes.stream().filter(o -> !o.won()).allMatch(o -> o.error() == BookingConflictException.class),
				"as demais devem receber BookingConflictException e nenhum outro erro: " + outcomes);
	}

	private BookingRequest slot(int dayOffset) {
		return new BookingRequest(providerSp, serviceSp, customer,
				LocalDateTime.of(LocalDate.of(2026, 11, 17).plusDays(dayOffset), LocalTime.of(10, 0)));
	}

	@Test
	void duasRequisicoesSimultaneasUmaVenceAOutraRecebeConflito() throws Exception {
		for (int round = 0; round < 20; round++) {
			assertOneWinnerOthersConflict(race(2, slot(round)));
		}

		assertEquals(20, count("SELECT count(*) FROM appointments WHERE status <> 'CANCELLED'"));
		assertEquals(20, count("SELECT count(*) FROM appointment_events"), "um evento por reserva vencedora");
	}

	@Test
	void muitasRequisicoesSimultaneasApenasUmaVence() throws Exception {
		assertOneWinnerOthersConflict(race(8, slot(0)));

		assertEquals(1, count("SELECT count(*) FROM appointments"));
		assertEquals(1, count("SELECT count(*) FROM appointment_events"));
	}
}
