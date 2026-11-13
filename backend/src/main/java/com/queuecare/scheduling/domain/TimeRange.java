package com.queuecare.scheduling.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * Intervalo de tempo {@code [startsAt, endsAt)}: o início conta, o fim não conta.
 * Exemplo: o intervalo 10:00 até 11:00 não se sobrepõe a um que começa às 11:00,
 * porque o fim (11:00) não conta.
 *
 * <p>Esta classe é o primeiro passo da regra R2 (ver docs/regras.md e docs/cenarios-r2.md).
 * Ela só responde se dois intervalos se sobrepõem; ela NÃO impede reserva dupla sob
 * requisições simultâneas (isso é papel do banco, ADR 0002).
 *
 * <p>Os instantes são {@link Instant} (UTC), como os {@code timestamptz} do banco.
 */
public record TimeRange(Instant startsAt, Instant endsAt) {

	public TimeRange {
		Objects.requireNonNull(startsAt, "startsAt não pode ser nulo");
		Objects.requireNonNull(endsAt, "endsAt não pode ser nulo");
		if (!endsAt.isAfter(startsAt)) {
			// Duração zero ou negativa (cenário J). Combina com o CHECK ends_at > starts_at no banco.
			throw new IllegalArgumentException("endsAt deve ser posterior a startsAt");
		}
	}

	/**
	 * Diz se este intervalo tem algum instante em comum com {@code other}.
	 *
	 * <p>Dois intervalos {@code [a, b)} e {@code [c, d)} se sobrepõem quando {@code a < d} e {@code c < b}.
	 * Intervalos que apenas se encostam (um termina quando o outro começa) NÃO se sobrepõem.
	 * A relação é simétrica: {@code x.overlaps(y) == y.overlaps(x)}.
	 */
	public boolean overlaps(TimeRange other) {
		Objects.requireNonNull(other, "other não pode ser nulo");
		return startsAt.isBefore(other.endsAt) && other.startsAt.isBefore(endsAt);
	}
}
