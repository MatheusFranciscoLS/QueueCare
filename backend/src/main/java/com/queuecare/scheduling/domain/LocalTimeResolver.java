package com.queuecare.scheduling.domain;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.zone.ZoneOffsetTransition;
import java.time.zone.ZoneRules;
import java.util.List;
import java.util.Objects;

/**
 * Regra R5 (parte 2): converte um horário local informado pelo usuário (por exemplo "03/11 às 02:30") em
 * instante, no fuso IANA do prestador, tratando horário de verão (ver docs/regras.md).
 *
 * <p>Política: não adivinhar. Horário inexistente e horário ambíguo são devolvidos como tal
 * ({@link ResolvedLocalTime.Nonexistent} e {@link ResolvedLocalTime.Ambiguous}) para a camada de cima decidir.
 * Usa as regras do fuso ({@link ZoneRules}), então vale para transições de qualquer duração
 * (por exemplo 30 minutos em Lord Howe) e para datas históricas.
 */
public final class LocalTimeResolver {

	private final ZoneId zone;

	public LocalTimeResolver(ZoneId zone) {
		this.zone = Objects.requireNonNull(zone, "zone não pode ser nulo");
	}

	public ZoneId zone() {
		return zone;
	}

	public ResolvedLocalTime resolve(LocalDateTime local) {
		Objects.requireNonNull(local, "local não pode ser nulo");
		ZoneRules rules = zone.getRules();
		List<ZoneOffset> offsets = rules.getValidOffsets(local);
		if (offsets.size() == 1) {
			return new ResolvedLocalTime.Resolved(local.toInstant(offsets.get(0)));
		}
		if (offsets.isEmpty()) {
			ZoneOffsetTransition gap = rules.getTransition(local);
			return new ResolvedLocalTime.Nonexistent(local, gap.getDateTimeAfter());
		}
		// Sobreposição: ordena os dois instantes cronologicamente, sem depender da ordem dos offsets.
		Instant first = local.toInstant(offsets.get(0));
		Instant second = local.toInstant(offsets.get(1));
		return first.isBefore(second)
				? new ResolvedLocalTime.Ambiguous(local, first, second)
				: new ResolvedLocalTime.Ambiguous(local, second, first);
	}
}
