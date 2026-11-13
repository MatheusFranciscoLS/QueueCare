package com.queuecare.scheduling.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Regra R3 (parte pura): o cliente só cancela dentro do prazo definido pelo prestador, isto é, com pelo menos
 * {@code deadline} de antecedência em relação ao início. Cancelar exatamente no limite é permitido; depois dele ou depois
 * do início, não. Prazo zero significa "pode cancelar até o instante do início".
 *
 * @param deadline antecedência mínima para cancelar; não pode ser negativa
 */
public record CancellationPolicy(Duration deadline) {

	public CancellationPolicy {
		Objects.requireNonNull(deadline, "deadline não pode ser nulo");
		if (deadline.isNegative()) {
			throw new IllegalArgumentException("deadline não pode ser negativo");
		}
	}

	/** O cliente ainda pode cancelar um agendamento que começa em {@code startsAt}, estando {@code now} agora? */
	public boolean allows(Instant startsAt, Instant now) {
		Objects.requireNonNull(startsAt, "startsAt não pode ser nulo");
		Objects.requireNonNull(now, "now não pode ser nulo");
		if (startsAt.isBefore(now)) {
			return false;
		}
		return Duration.between(now, startsAt).compareTo(deadline) >= 0;
	}
}
