package com.queuecare.scheduling.application;

import com.queuecare.scheduling.application.ProviderExceptions.InvalidConfigurationException;
import com.queuecare.scheduling.application.ProviderExceptions.ProviderNotConfiguredException;
import com.queuecare.scheduling.persistence.AppointmentRepository;
import com.queuecare.scheduling.persistence.Provider;
import com.queuecare.scheduling.persistence.ProviderRepository;
import com.queuecare.scheduling.persistence.TimeOff;
import com.queuecare.scheduling.persistence.TimeOffRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Folgas, feriados e férias (ADR 0024). O estúdio fecha por DIAS INTEIROS, na data local do fuso dele. Fechar um período NUNCA cancela nem
 * remarca agendamentos sozinho (isso seria decidir pelo dono e pelo tutor): a resposta diz quantos agendamentos ativos já existem no período,
 * para a equipe tratá-los um a um. Daí em diante a reserva e a remarcação recusam o período (CLOSED_DAY) e a consulta de horários livres vem vazia.
 */
@Service
public class TimeOffService {

	static final int MAX_DAYS_PER_PERIOD = 366;
	static final int MAX_DAYS_AHEAD = 730;
	static final int MAX_PERIODS = 200;
	static final int MAX_REASON = 80;

	private final ProviderRepository providers;
	private final TimeOffRepository timeOff;
	private final AppointmentRepository appointments;
	private final Clock clock;

	public TimeOffService(ProviderRepository providers, TimeOffRepository timeOff, AppointmentRepository appointments, Clock clock) {
		this.providers = providers;
		this.timeOff = timeOff;
		this.appointments = appointments;
		this.clock = clock;
	}

	/** O período criado e quantos agendamentos ATIVOS (marcados, no balcão ou em atendimento) já caíam nele. */
	public record Created(TimeOff period, long affectedAppointments) {
	}

	/** Períodos em andamento ou futuros do negócio do dono. */
	@Transactional(readOnly = true)
	public List<TimeOff> upcoming(UUID ownerUserId) {
		Provider provider = providers.findByUserId(ownerUserId).orElseThrow(ProviderNotConfiguredException::new);
		return upcomingOf(provider);
	}

	/** O mesmo, para quem já tem o negócio em mãos (a agenda da equipe mostra o aviso "fechado"). */
	@Transactional(readOnly = true)
	public List<TimeOff> upcomingOf(Provider provider) {
		LocalDate today = LocalDate.now(clock.withZone(ZoneId.of(provider.getTimeZone())));
		return timeOff.findUpcoming(provider.getId(), today);
	}

	@Transactional
	public Created create(UUID ownerUserId, LocalDate startsOn, LocalDate endsOn, String reason) {
		Provider provider = providers.findByUserId(ownerUserId).orElseThrow(ProviderNotConfiguredException::new);
		ZoneId zone = ZoneId.of(provider.getTimeZone());
		LocalDate today = LocalDate.now(clock.withZone(zone));
		if (startsOn == null || endsOn == null) {
			throw new InvalidConfigurationException("dates", "informe o primeiro e o último dia");
		}
		if (endsOn.isBefore(startsOn)) {
			throw new InvalidConfigurationException("dates", "o último dia não pode ser antes do primeiro");
		}
		if (endsOn.isBefore(today)) {
			throw new InvalidConfigurationException("dates", "o período já passou");
		}
		if (startsOn.isAfter(today.plusDays(MAX_DAYS_AHEAD))) {
			throw new InvalidConfigurationException("dates", "no máximo " + MAX_DAYS_AHEAD + " dias à frente");
		}
		if (startsOn.plusDays(MAX_DAYS_PER_PERIOD).isBefore(endsOn)) {
			throw new InvalidConfigurationException("dates", "um período tem no máximo " + MAX_DAYS_PER_PERIOD + " dias");
		}
		String cleanReason = reason == null || reason.isBlank() ? null : reason.trim();
		if (cleanReason != null && cleanReason.length() > MAX_REASON) {
			throw new InvalidConfigurationException("reason", "até " + MAX_REASON + " caracteres");
		}
		if (timeOff.existsOverlapping(provider.getId(), startsOn, endsOn)) {
			throw new InvalidConfigurationException("dates", "já existe uma folga que se sobrepõe a esse período");
		}
		if (timeOff.countByProviderId(provider.getId()) >= MAX_PERIODS) {
			throw new InvalidConfigurationException("dates", "no máximo " + MAX_PERIODS + " períodos: remova os antigos");
		}
		TimeOff saved = timeOff.save(new TimeOff(provider.getId(), startsOn, endsOn, cleanReason, Instant.now(clock)));
		Instant from = startsOn.atStartOfDay(zone).toInstant();
		Instant to = endsOn.plusDays(1).atStartOfDay(zone).toInstant();
		long affected = appointments.findActiveOverlapping(provider.getId(), from, to).size();
		return new Created(saved, affected);
	}

	/** Remove o período do PRÓPRIO negócio; de outro negócio ou inexistente, a mesma resposta (404). */
	@Transactional
	public void delete(UUID ownerUserId, UUID id) {
		Provider provider = providers.findByUserId(ownerUserId).orElseThrow(ProviderNotConfiguredException::new);
		TimeOff period = timeOff.findByIdAndProviderId(id, provider.getId()).orElseThrow(TimeOffNotFoundException::new);
		timeOff.delete(period);
	}

	/** Períodos de um estúdio ativo, de hoje em diante, para o tutor ver antes de escolher o dia (404 se o estúdio não existe ou está inativo). */
	@Transactional(readOnly = true)
	public List<TimeOff> upcomingOfActive(UUID providerId) {
		Provider provider = providers.findActiveById(providerId).orElseThrow(() -> new BookingRejectedException(BookingRejectedException.Reason.PROVIDER_NOT_FOUND));
		return upcomingOf(provider);
	}

	/** O período em que o estúdio está fechado naquele dia local, se houver. */
	@Transactional(readOnly = true)
	public Optional<TimeOff> closureOn(UUID providerId, LocalDate day) {
		return timeOff.findCovering(providerId, day).stream().findFirst();
	}

	/** Período inexistente ou de outro negócio (HTTP 404). */
	public static class TimeOffNotFoundException extends RuntimeException {
		public TimeOffNotFoundException() {
			super("folga não encontrada");
		}
	}
}
