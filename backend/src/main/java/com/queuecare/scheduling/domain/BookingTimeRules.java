package com.queuecare.scheduling.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Regra R5 (parte 1): rejeitar agendamento no passado e respeitar a antecedência mínima configurada
 * pelo prestador (ver docs/regras.md).
 *
 * <p>O momento atual é sempre recebido como parâmetro ({@code now}), para que os testes sejam determinísticos;
 * a camada de serviço fornecerá um {@link java.time.Clock} depois.
 *
 * @param minimumNotice antecedência mínima; zero é permitido (aceita agendar para agora), negativo não
 */
public record BookingTimeRules(Duration minimumNotice) {

	public BookingTimeRules {
		Objects.requireNonNull(minimumNotice, "minimumNotice não pode ser nulo");
		if (minimumNotice.isNegative()) {
			throw new IllegalArgumentException("minimumNotice não pode ser negativo");
		}
	}

	/**
	 * Confere o início do intervalo contra o momento atual:
	 * <ul>
	 *   <li>{@link BookingTimeCheck#IN_THE_PAST}: o início é anterior a {@code now};</li>
	 *   <li>{@link BookingTimeCheck#TOO_SOON}: o início é a partir de {@code now}, mas falta menos que a antecedência mínima;</li>
	 *   <li>{@link BookingTimeCheck#OK}: a diferença é maior ou igual à antecedência mínima (igual ao limite é permitido).</li>
	 * </ul>
	 *
	 * <p>A comparação usa a diferença entre os instantes, e não {@code now.plus(minimumNotice)}, para não estourar
	 * o limite de {@link Instant} com antecedências extremas.
	 */
	public BookingTimeCheck check(TimeRange range, Instant now) {
		Objects.requireNonNull(range, "range não pode ser nulo");
		Objects.requireNonNull(now, "now não pode ser nulo");
		if (range.startsAt().isBefore(now)) {
			return BookingTimeCheck.IN_THE_PAST;
		}
		Duration lead = Duration.between(now, range.startsAt());
		return lead.compareTo(minimumNotice) < 0 ? BookingTimeCheck.TOO_SOON : BookingTimeCheck.OK;
	}
}
