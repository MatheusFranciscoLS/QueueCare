package com.queuecare.scheduling.application;

import com.queuecare.auth.RowLocks;
import com.queuecare.pets.Pet;
import com.queuecare.scheduling.application.BookingRejectedException.Reason;
import com.queuecare.scheduling.domain.AvailabilityPolicy;
import com.queuecare.scheduling.domain.AvailabilityWindow;
import com.queuecare.scheduling.domain.BookingTimeCheck;
import com.queuecare.scheduling.domain.BookingTimeRules;
import com.queuecare.scheduling.domain.LocalTimeResolver;
import com.queuecare.scheduling.domain.ResolvedLocalTime;
import com.queuecare.scheduling.domain.TimeRange;
import com.queuecare.scheduling.persistence.Appointment;
import com.queuecare.scheduling.persistence.AppointmentEvent;
import com.queuecare.scheduling.persistence.AppointmentEventRepository;
import com.queuecare.scheduling.persistence.AppointmentEventType;
import com.queuecare.scheduling.persistence.AppointmentRepository;
import com.queuecare.scheduling.persistence.AvailabilityWindowRepository;
import com.queuecare.scheduling.persistence.Provider;
import com.queuecare.scheduling.persistence.ProviderRepository;
import com.queuecare.scheduling.persistence.ServiceOffering;
import com.queuecare.scheduling.persistence.ServiceOfferingRepository;
import com.queuecare.scheduling.persistence.UserAccount;
import com.queuecare.scheduling.persistence.UserAccountRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Caso de uso "agendar": junta as regras puras (R5 horário local e antecedência, R1 disponibilidade, R2 sobreposição)
 * com a persistência.
 *
 * <p>Fluxo: converter o horário local (R5) -> calcular o fim pela duração do serviço -> antecedência e passado (R5) ->
 * expediente (R1) -> checagem de sobreposição para dar mensagem clara (R2, camada da aplicação) -> gravar.
 * A checagem prévia NÃO impede reserva dupla sob concorrência; quem garante é a restrição de exclusão do banco (ADR 0002).
 * O erro do banco é traduzido para {@link BookingConflictException} DEPOIS do rollback da transação que falhou
 * (no PostgreSQL ela fica abortada), e só quando for o conflito de horário (23P01 da restrição ou 40P01), nunca para
 * outros erros de integridade.
 */
@Service
public class BookingService {

	private final ProviderRepository providers;
	private final ServiceOfferingRepository services;
	private final UserAccountRepository users;
	private final AvailabilityWindowRepository windows;
	private final AppointmentRepository appointments;
	private final AppointmentEventRepository events;
	private final RowLocks locks;
	private final TransactionTemplate transaction;
	private final Clock clock;

	public BookingService(ProviderRepository providers, ServiceOfferingRepository services, UserAccountRepository users,
			AvailabilityWindowRepository windows, AppointmentRepository appointments, AppointmentEventRepository events,
			RowLocks locks, PlatformTransactionManager transactionManager, Clock clock) {
		this.providers = providers;
		this.services = services;
		this.users = users;
		this.windows = windows;
		this.appointments = appointments;
		this.events = events;
		this.locks = locks;
		this.transaction = new TransactionTemplate(transactionManager);
		this.clock = clock;
	}

	/**
	 * Agenda um horário.
	 *
	 * @throws BookingRejectedException regra de negócio ou dado inválido (veja {@link Reason})
	 * @throws BookingConflictException o horário está ocupado, inclusive quando outra requisição o ocupou ao mesmo tempo
	 */
	public Appointment book(BookingRequest request) {
		try {
			// A transação termina (commit ou rollback) antes de qualquer tradução de erro abaixo.
			return transaction.execute(status -> bookInTransaction(request));
		} catch (RuntimeException e) {
			if (SchedulingConflictDetector.isConflict(e)) {
				throw new BookingConflictException(e);
			}
			throw e;
		}
	}

	private Appointment bookInTransaction(BookingRequest request) {
		Provider provider = providers.findActiveById(request.providerId())
				.orElseThrow(() -> new BookingRejectedException(Reason.PROVIDER_NOT_FOUND));
		ServiceOffering service = services.findByIdAndProviderId(request.serviceId(), provider.getId())
				.filter(ServiceOffering::isActive)
				.orElseThrow(() -> new BookingRejectedException(Reason.SERVICE_NOT_FOUND_OR_INACTIVE));
		UserAccount customer = users.findById(request.customerId())
				.filter(UserAccount::isActive)
				.orElseThrow(() -> new BookingRejectedException(Reason.CUSTOMER_NOT_FOUND_OR_INACTIVE));

		Pet pet = lockPetOf(request, customer);

		ZoneId zone = ZoneId.of(provider.getTimeZone());
		Instant start = resolveStart(zone, request);
		TimeRange range = new TimeRange(start, start.plus(Duration.ofMinutes(service.getDurationMinutes())));

		Instant now = clock.instant();
		BookingTimeCheck timeCheck = new BookingTimeRules(Duration.ofMinutes(provider.getMinimumNoticeMinutes())).check(range, now);
		if (timeCheck == BookingTimeCheck.IN_THE_PAST) {
			throw new BookingRejectedException(Reason.IN_THE_PAST);
		}
		if (timeCheck == BookingTimeCheck.TOO_SOON) {
			throw new BookingRejectedException(Reason.TOO_SOON);
		}

		if (!availabilityOf(provider, zone).allows(range)) {
			throw new BookingRejectedException(Reason.OUTSIDE_AVAILABILITY);
		}

		if (appointments.existsActiveOverlapping(provider.getId(), range.startsAt(), range.endsAt())) {
			throw new BookingConflictException();
		}

		Appointment draft = new Appointment(provider.getId(), service.getId(), customer.getId(), range.startsAt(), range.endsAt(), now);
		if (pet != null) {
			draft.attachPet(pet); // cópia dos dados do pet no momento da reserva (ADR 0016)
		}
		Appointment appointment = appointments.saveAndFlush(draft);
		events.save(new AppointmentEvent(appointment.getId(), customer.getId(), AppointmentEventType.SCHEDULED, now, null));
		return appointment;
	}

	/**
	 * O pet da reserva (ADR 0016), ou nulo se a reserva não escolheu um. Bloqueia a LINHA do pet (só se for do cliente) e SÓ DEPOIS confere
	 * {@code active}: arquivar bloqueia a mesma linha, então ou a reserva vence (e o arquivamento seguinte continua permitido) ou o
	 * arquivamento vence e a reserva falha com PET_INACTIVE. Ler {@code active} sem bloquear não bastaria: entre a leitura e a gravação o
	 * tutor poderia arquivar. Pet inexistente e pet de outro cliente têm a MESMA resposta (PET_NOT_FOUND), e a conferência do dono vem antes
	 * da de ativo. Ordem global de bloqueios: cliente, pet, agendamento (aqui, o cliente só é lido).
	 */
	private Pet lockPetOf(BookingRequest request, UserAccount customer) {
		if (request.petId() == null) {
			return null;
		}
		Pet pet = locks.lockPet(request.petId(), customer.getId()).orElseThrow(() -> new BookingRejectedException(Reason.PET_NOT_FOUND));
		if (!pet.isActive()) {
			throw new BookingRejectedException(Reason.PET_INACTIVE);
		}
		return pet;
	}

	private Instant resolveStart(ZoneId zone, BookingRequest request) {
		ResolvedLocalTime resolved = new LocalTimeResolver(zone).resolve(request.localStart());
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
