package com.queuecare.scheduling.domain;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.Objects;

/**
 * Uma janela semanal de atendimento, em horário LOCAL do prestador:
 * por exemplo, segunda-feira das 08:00 às 12:00.
 *
 * <p>A janela é o intervalo {@code [start, end)}, não cruza a meia-noite e o fim deve ser
 * posterior ao início. Como {@link LocalTime} não representa 24:00, um expediente que vai até o
 * fim do dia termina em 23:59.
 */
public record AvailabilityWindow(DayOfWeek dayOfWeek, LocalTime start, LocalTime end) {

	public AvailabilityWindow {
		Objects.requireNonNull(dayOfWeek, "dayOfWeek não pode ser nulo");
		Objects.requireNonNull(start, "start não pode ser nulo");
		Objects.requireNonNull(end, "end não pode ser nulo");
		if (!end.isAfter(start)) {
			throw new IllegalArgumentException("end deve ser posterior a start");
		}
	}

	/** Duas janelas do mesmo dia se sobrepõem quando {@code a.start < b.end} e {@code b.start < a.end}. */
	boolean overlaps(AvailabilityWindow other) {
		return dayOfWeek == other.dayOfWeek && start.isBefore(other.end) && other.start.isBefore(end);
	}
}
