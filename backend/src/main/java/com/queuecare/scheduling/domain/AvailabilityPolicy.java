package com.queuecare.scheduling.domain;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.zone.ZoneOffsetTransition;
import java.time.zone.ZoneRules;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Regra R1 (disponibilidade): um agendamento só pode ocorrer dentro do expediente do prestador
 * e deve caber inteiro nele (ver docs/regras.md).
 *
 * <p>A política reúne o fuso IANA do prestador e as janelas semanais em horário local. É imutável.
 * Uma lista vazia de janelas é válida e significa que o prestador não atende em nenhum dia.
 *
 * <p>Fora do escopo desta classe (R5): antecedência mínima, agendamento no passado, horários locais
 * inexistentes ou ambíguos informados pelo usuário e exceções de data (feriados).
 */
public final class AvailabilityPolicy {

	private final ZoneId zone;
	private final List<AvailabilityWindow> windows;

	/**
	 * @throws NullPointerException se {@code zone}, a lista ou alguma janela for nulo
	 * @throws IllegalArgumentException se duas janelas do mesmo dia se sobrepuserem
	 *     (janelas coladas, como 08:00-12:00 e 12:00-18:00, são aceitas)
	 */
	public AvailabilityPolicy(ZoneId zone, List<AvailabilityWindow> windows) {
		this.zone = Objects.requireNonNull(zone, "zone não pode ser nulo");
		this.windows = List.copyOf(Objects.requireNonNull(windows, "windows não pode ser nulo"));
		for (int i = 0; i < this.windows.size(); i++) {
			for (int j = i + 1; j < this.windows.size(); j++) {
				if (this.windows.get(i).overlaps(this.windows.get(j))) {
					throw new IllegalArgumentException("janelas sobrepostas: " + this.windows.get(i) + " e " + this.windows.get(j));
				}
			}
		}
	}

	public ZoneId zone() {
		return zone;
	}

	public List<AvailabilityWindow> windows() {
		return windows;
	}

	/**
	 * Diz se o intervalo cabe inteiro no expediente do prestador.
	 *
	 * <p>Os instantes são convertidos para o fuso do prestador. O intervalo só é permitido se
	 * início e fim caem no MESMO dia local (terminar exatamente à meia-noite local conta como dia seguinte,
	 * então é rejeitado) e se o horário local de início e de fim fica dentro de um mesmo bloco de expediente.
	 * Janelas coladas (uma termina quando a outra começa) formam um bloco contínuo; uma lacuna de qualquer
	 * tamanho interrompe o bloco.
	 *
	 * <p>Se durante o intervalo o relógio local volta para trás (fim do horário de verão), o intervalo é
	 * rejeitado por segurança, pois o mesmo horário local aconteceria duas vezes. O avanço do relógio (início
	 * do horário de verão) é permitido e o intervalo é avaliado pelo relógio local resultante.
	 */
	public boolean allows(TimeRange range) {
		Objects.requireNonNull(range, "range não pode ser nulo");
		if (crossesClocksGoingBack(range)) {
			return false;
		}
		ZonedDateTime start = range.startsAt().atZone(zone);
		LocalDateTime end = localEnd(range, start);
		if (!start.toLocalDate().equals(end.toLocalDate())) {
			return false;
		}
		LocalTime localStart = start.toLocalTime();
		LocalTime localEnd = end.toLocalTime();
		if (!localEnd.isAfter(localStart)) {
			return false;
		}
		for (LocalTimeBlock block : blocksOf(start.getDayOfWeek())) {
			if (!localStart.isBefore(block.start()) && !localEnd.isAfter(block.end())) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Data e hora locais do fim do intervalo. Se não há mudança de offset dentro do intervalo, o fim é lido com o
	 * mesmo offset do início (um intervalo que termina exatamente no instante da volta do relógio ainda pertence à
	 * primeira passagem). Se há avanço do relógio no meio, usa-se o fuso, que dá o relógio local resultante.
	 */
	private LocalDateTime localEnd(TimeRange range, ZonedDateTime start) {
		ZoneOffset lastOffset = zone.getRules().getOffset(range.endsAt().minusNanos(1));
		if (lastOffset.equals(start.getOffset())) {
			return range.endsAt().atOffset(start.getOffset()).toLocalDateTime();
		}
		return range.endsAt().atZone(zone).toLocalDateTime();
	}

	/**
	 * Diz se, durante o intervalo, o relógio local do prestador volta para trás (fim do horário de verão).
	 * Nesse caso o mesmo horário local acontece duas vezes e a comparação por relógio local não é confiável,
	 * então o intervalo é rejeitado por segurança. O avanço do relógio (início do horário de verão) não é problema.
	 */
	private boolean crossesClocksGoingBack(TimeRange range) {
		ZoneRules rules = zone.getRules();
		ZoneOffsetTransition transition = rules.nextTransition(range.startsAt());
		while (transition != null && transition.getInstant().isBefore(range.endsAt())) {
			if (transition.isOverlap()) {
				return true;
			}
			transition = rules.nextTransition(transition.getInstant());
		}
		return false;
	}

	/** Blocos contínuos de expediente do dia: janelas coladas são unidas. */
	private List<LocalTimeBlock> blocksOf(DayOfWeek day) {
		List<AvailabilityWindow> ofDay = windows.stream()
				.filter(w -> w.dayOfWeek() == day)
				.sorted(Comparator.comparing(AvailabilityWindow::start))
				.toList();
		List<LocalTimeBlock> blocks = new ArrayList<>();
		for (AvailabilityWindow window : ofDay) {
			int last = blocks.size() - 1;
			if (last >= 0 && blocks.get(last).end().equals(window.start())) {
				blocks.set(last, new LocalTimeBlock(blocks.get(last).start(), window.end()));
			} else {
				blocks.add(new LocalTimeBlock(window.start(), window.end()));
			}
		}
		return blocks;
	}

	private record LocalTimeBlock(LocalTime start, LocalTime end) {
	}
}
