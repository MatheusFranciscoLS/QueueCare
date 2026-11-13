package com.queuecare.scheduling.domain;

import java.time.Instant;

/**
 * Intervalo de tempo {@code [startsAt, endsAt)}: o início conta, o fim não conta.
 * Exemplo: o intervalo 10:00 até 11:00 não se sobrepõe a um que começa às 11:00,
 * porque o fim (11:00) não conta.
 *
 * <p>Esta classe é o primeiro passo da regra R2 (ver docs/regras.md e docs/cenarios-r2.md).
 * Ela só responde se dois intervalos se sobrepõem; ela NÃO impede reserva dupla sob
 * requisições simultâneas (isso é papel do banco, ADR 0002).
 */
public record TimeRange(Instant startsAt, Instant endsAt) {

	public TimeRange {
		// TODO(matheus): decidir e implementar a validação de intervalos inválidos
		// (nulos, fim igual ou anterior ao início). Veja o cenário J em docs/cenarios-r2.md.
	}

	/**
	 * Diz se este intervalo tem algum instante em comum com {@code other}.
	 */
	public boolean overlaps(TimeRange other) {
		// TODO(matheus): implementar. Dica de estudo: desenhe os dois intervalos numa linha do tempo
		// (docs/estudo-r2.md) e pense em quando eles NÃO se sobrepõem.
		throw new UnsupportedOperationException("TODO(matheus): implementar overlaps");
	}
}
