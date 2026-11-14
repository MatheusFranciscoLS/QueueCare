package com.queuecare.scheduling.application;

import com.queuecare.scheduling.application.BookingRejectedException.Reason;
import com.queuecare.scheduling.domain.AvailabilityPolicy;
import com.queuecare.scheduling.domain.AvailabilityWindow;
import com.queuecare.scheduling.domain.BookingTimeCheck;
import com.queuecare.scheduling.domain.BookingTimeRules;
import com.queuecare.scheduling.domain.LocalTimeResolver;
import com.queuecare.scheduling.domain.ResolvedLocalTime;
import com.queuecare.scheduling.domain.TimeRange;
import com.queuecare.scheduling.persistence.Appointment;
import com.queuecare.scheduling.persistence.AppointmentRepository;
import com.queuecare.scheduling.persistence.AvailabilityWindowEntity;
import com.queuecare.scheduling.persistence.AvailabilityWindowRepository;
import com.queuecare.scheduling.persistence.Provider;
import com.queuecare.scheduling.persistence.ProviderRepository;
import com.queuecare.scheduling.persistence.ServiceOffering;
import com.queuecare.scheduling.persistence.ServiceOfferingRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Consulta de horários livres de um serviço num dia (data LOCAL do prestador). Reaproveita as regras puras:
 * horário local sem ambiguidade (R5), passado e antecedência (R5), expediente (R1) e ausência de sobreposição com
 * agendamentos ativos (R2). É uma CONSULTA: reservar continua passando por {@link BookingService}, e a trava do banco
 * é quem garante que dois clientes não ocupem o mesmo horário (um horário listado pode ser tomado por outro antes).
 */
@Service
public class SlotService {

	/** Grade de horários iniciais oferecidos, em minutos. */
	static final int STEP_MINUTES = 15;
	/** Quantos dias à frente podem ser consultados. */
	static final int MAX_DAYS_AHEAD = 90;

	private final ProviderRepository providers;
	private final ServiceOfferingRepository services;
	private final AvailabilityWindowRepository windows;
	private final AppointmentRepository appointments;
	private final Clock clock;

	public SlotService(ProviderRepository providers, ServiceOfferingRepository services, AvailabilityWindowRepository windows,
			AppointmentRepository appointments, Clock clock) {
		this.providers = providers;
		this.services = services;
		this.windows = windows;
		this.appointments = appointments;
		this.clock = clock;
	}

	/** Um horário livre: início local (como o cliente digita) e os instantes UTC correspondentes. */
	public record Slot(LocalDateTime localStart, Instant startsAt, Instant endsAt) {
	}

	@Transactional(readOnly = true)
	public List<Slot> freeSlots(UUID providerId, UUID serviceId, LocalDate date) {
		Provider provider = providers.findActiveById(providerId).orElseThrow(() -> new BookingRejectedException(Reason.PROVIDER_NOT_FOUND));
		ServiceOffering service = services.findByIdAndProviderId(serviceId, providerId)
				.filter(ServiceOffering::isActive)
				.orElseThrow(() -> new BookingRejectedException(Reason.SERVICE_NOT_FOUND_OR_INACTIVE));
		ZoneId zone = ZoneId.of(provider.getTimeZone());
		LocalDate today = LocalDate.now(clock.withZone(zone));
		if (date.isBefore(today) || date.isAfter(today.plusDays(MAX_DAYS_AHEAD))) {
			return List.of();
		}

		List<AvailabilityWindowEntity> entities = windows.findByProviderId(providerId);
		AvailabilityPolicy policy = new AvailabilityPolicy(zone, entities.stream()
				.map(w -> new AvailabilityWindow(w.dayOfWeek(), w.getStartTime(), w.getEndTime())).toList());
		BookingTimeRules rules = new BookingTimeRules(Duration.ofMinutes(provider.getMinimumNoticeMinutes()));
		LocalTimeResolver resolver = new LocalTimeResolver(zone);
		Instant now = clock.instant();
		Duration duration = Duration.ofMinutes(service.getDurationMinutes());

		Instant dayStart = date.atStartOfDay(zone).toInstant();
		Instant dayEnd = date.plusDays(1).atStartOfDay(zone).toInstant();
		List<TimeRange> taken = takenRanges(providerId, dayStart, dayEnd);

		List<Slot> slots = new ArrayList<>();
		for (LocalTime[] block : continuousBlocks(entities, date.getDayOfWeek())) {
			LocalTime time = block[0];
			while (fits(time, duration, block[1])) {
				LocalDateTime local = date.atTime(time);
				if (resolver.resolve(local) instanceof ResolvedLocalTime.Resolved resolved) {
					TimeRange range = new TimeRange(resolved.instant(), resolved.instant().plus(duration));
					if (rules.check(range, now) == BookingTimeCheck.OK && policy.allows(range) && taken.stream().noneMatch(range::overlaps)) {
						slots.add(new Slot(local, range.startsAt(), range.endsAt()));
					}
				}
				LocalTime next = time.plusMinutes(STEP_MINUTES);
				if (!next.isAfter(time)) {
					break; // passou da meia-noite
				}
				time = next;
			}
		}
		slots.sort(Comparator.comparing(Slot::startsAt));
		return slots;
	}

	/** Blocos contínuos de expediente do dia: janelas coladas (uma termina quando a outra começa) formam um só bloco, como em AvailabilityPolicy. */
	private static List<LocalTime[]> continuousBlocks(List<AvailabilityWindowEntity> entities, java.time.DayOfWeek day) {
		List<AvailabilityWindowEntity> ofDay = entities.stream().filter(w -> w.dayOfWeek() == day)
				.sorted(Comparator.comparing(AvailabilityWindowEntity::getStartTime)).toList();
		List<LocalTime[]> blocks = new ArrayList<>();
		for (AvailabilityWindowEntity w : ofDay) {
			LocalTime[] last = blocks.isEmpty() ? null : blocks.get(blocks.size() - 1);
			if (last != null && last[1].equals(w.getStartTime())) {
				last[1] = w.getEndTime();
			} else {
				blocks.add(new LocalTime[] {w.getStartTime(), w.getEndTime()});
			}
		}
		return blocks;
	}

	/** O serviço que começa em {@code time} termina até o fim da janela, sem passar da meia-noite? */
	private static boolean fits(LocalTime time, Duration duration, LocalTime windowEnd) {
		LocalTime end = time.plus(duration);
		return end.isAfter(time) && !end.isAfter(windowEnd);
	}

	private List<TimeRange> takenRanges(UUID providerId, Instant dayStart, Instant dayEnd) {
		return appointments.findActiveOverlapping(providerId, dayStart, dayEnd).stream()
				.map(a -> new TimeRange(a.getStartsAt(), a.getEndsAt())).toList();
	}
}
