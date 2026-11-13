package com.queuecare.scheduling.domain;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * Resultado de converter um horário LOCAL digitado pelo usuário em instante (UTC). Existem exatamente três casos,
 * por causa do horário de verão: um instante, nenhum instante (a hora foi pulada) ou dois instantes (a hora se repete).
 * O sistema não adivinha: quem chama decide o que fazer (por exemplo, responder 400 pedindo outro horário).
 */
public sealed interface ResolvedLocalTime {

	/** Existe exatamente um instante para o horário local. */
	record Resolved(Instant instant) implements ResolvedLocalTime {
		public Resolved {
			Objects.requireNonNull(instant, "instant não pode ser nulo");
		}
	}

	/**
	 * O horário local não existe (cai na lacuna em que o relógio avança, como 02:30 em 08/03/2026 em Nova York).
	 *
	 * @param requested o horário pedido
	 * @param nextValid o início do próximo período válido (por exemplo 03:00, não 03:30)
	 */
	record Nonexistent(LocalDateTime requested, LocalDateTime nextValid) implements ResolvedLocalTime {
		public Nonexistent {
			Objects.requireNonNull(requested, "requested não pode ser nulo");
			Objects.requireNonNull(nextValid, "nextValid não pode ser nulo");
		}
	}

	/**
	 * O horário local acontece duas vezes (o relógio volta, como 01:30 em 01/11/2026 em Nova York).
	 *
	 * @param requested o horário pedido
	 * @param earlier a primeira ocorrência (cronologicamente anterior)
	 * @param later a segunda ocorrência
	 */
	record Ambiguous(LocalDateTime requested, Instant earlier, Instant later) implements ResolvedLocalTime {
		public Ambiguous {
			Objects.requireNonNull(requested, "requested não pode ser nulo");
			Objects.requireNonNull(earlier, "earlier não pode ser nulo");
			Objects.requireNonNull(later, "later não pode ser nulo");
			if (!earlier.isBefore(later)) {
				throw new IllegalArgumentException("earlier deve ser anterior a later");
			}
		}
	}
}
