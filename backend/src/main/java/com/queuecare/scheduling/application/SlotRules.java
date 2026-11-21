package com.queuecare.scheduling.application;

import com.queuecare.scheduling.application.BookingRejectedException.Reason;
import com.queuecare.scheduling.domain.AvailabilityPolicy;
import com.queuecare.scheduling.domain.AvailabilityWindow;
import com.queuecare.scheduling.domain.BookingTimeCheck;
import com.queuecare.scheduling.domain.BookingTimeRules;
import com.queuecare.scheduling.domain.LocalTimeResolver;
import com.queuecare.scheduling.domain.ResolvedLocalTime;
import com.queuecare.scheduling.domain.TimeRange;
import com.queuecare.scheduling.persistence.AppointmentRepository;
import com.queuecare.scheduling.persistence.AvailabilityWindowRepository;
import com.queuecare.scheduling.persistence.Provider;
import com.queuecare.scheduling.persistence.TimeOffRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * As regras do HORÁRIO de um agendamento, num lugar só (ADR 0017): usadas por quem reserva ({@link BookingService}) e por quem remarca
 * ({@link RescheduleService}), para que as duas operações nunca divirjam. Ordem: horário local sem ambiguidade (R5), passado e
 * antecedência mínima (R5), dia fechado por folga/feriado/férias (ADR 0024), expediente (R1) e ausência de sobreposição com outro agendamento ativo (R2, só para dar mensagem clara).
 * A checagem de sobreposição NÃO impede reserva dupla sob concorrência: quem garante é a restrição de exclusão do banco (ADR 0002).
 * Chamar dentro de uma transação.
 */
@Component
public class SlotRules {

	private final AvailabilityWindowRepository windows;
	private final AppointmentRepository appointments;
	private final TimeOffRepository timeOff;

	public SlotRules(AvailabilityWindowRepository windows, AppointmentRepository appointments, TimeOffRepository timeOff) {
		this.windows = windows;
		this.appointments = appointments;
		this.timeOff = timeOff;
	}

	/**
	 * Valida o horário local pedido e devolve o intervalo em UTC.
	 *
	 * @param duration duração do atendimento (a do serviço, na reserva; a do próprio agendamento, na remarcação)
	 * @param ignoring agendamento que NÃO conta como ocupante do horário (o próprio, ao remarcar: ele não conflita consigo mesmo); nulo na reserva
	 * @throws BookingRejectedException horário inválido, no passado, perto demais ou fora do expediente
	 * @throws BookingConflictException o horário está ocupado por outro agendamento
	 */
	public TimeRange validate(Provider provider, LocalDateTime localStart, Duration duration, Instant now, UUID ignoring) {
		ZoneId zone = ZoneId.of(provider.getTimeZone());
		Instant start = resolveStart(zone, localStart);
		TimeRange range = new TimeRange(start, start.plus(duration));

		BookingTimeCheck timeCheck = new BookingTimeRules(Duration.ofMinutes(provider.getMinimumNoticeMinutes())).check(range, now);
		if (timeCheck == BookingTimeCheck.IN_THE_PAST) {
			throw new BookingRejectedException(Reason.IN_THE_PAST);
		}
		if (timeCheck == BookingTimeCheck.TOO_SOON) {
			throw new BookingRejectedException(Reason.TOO_SOON);
		}

		// Folga, feriado ou férias (ADR 0024): o dia local do início fecha o estúdio, mesmo que o expediente semanal tenha janela nele.
		if (!timeOff.findCovering(provider.getId(), range.startsAt().atZone(zone).toLocalDate()).isEmpty()) {
			throw new BookingRejectedException(Reason.CLOSED_DAY);
		}
		if (!availabilityOf(provider, zone).allows(range)) {
			throw new BookingRejectedException(Reason.OUTSIDE_AVAILABILITY);
		}

		boolean taken = ignoring == null
				? appointments.existsActiveOverlapping(provider.getId(), range.startsAt(), range.endsAt())
				: appointments.existsActiveOverlappingExcept(provider.getId(), range.startsAt(), range.endsAt(), ignoring);
		if (taken) {
			throw new BookingConflictException();
		}
		return range;
	}

	private static Instant resolveStart(ZoneId zone, LocalDateTime localStart) {
		ResolvedLocalTime resolved = new LocalTimeResolver(zone).resolve(localStart);
		return switch (resolved) {
			case ResolvedLocalTime.Resolved r -> r.instant();
			case ResolvedLocalTime.Nonexistent n -> throw new BookingRejectedException(Reason.NONEXISTENT_LOCAL_TIME);
			case ResolvedLocalTime.Ambiguous a -> throw new BookingRejectedException(Reason.AMBIGUOUS_LOCAL_TIME);
		};
	}

	private AvailabilityPolicy availabilityOf(Provider provider, ZoneId zone) {
		List<AvailabilityWindow> list = windows.findByProviderId(provider.getId()).stream()
				.map(w -> new AvailabilityWindow(w.dayOfWeek(), w.getStartTime(), w.getEndTime()))
				.toList();
		return new AvailabilityPolicy(zone, list);
	}
}
