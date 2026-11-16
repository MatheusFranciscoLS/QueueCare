package com.queuecare.scheduling.application;

import com.queuecare.auth.RowLocks;
import com.queuecare.scheduling.application.AppointmentExceptions.AppointmentNotFoundException;
import com.queuecare.scheduling.application.AppointmentExceptions.RescheduleRejectedException;
import com.queuecare.scheduling.application.AppointmentExceptions.RescheduleRejectedException.Reason;
import com.queuecare.scheduling.domain.CancellationPolicy;
import com.queuecare.scheduling.domain.TimeRange;
import com.queuecare.scheduling.persistence.Appointment;
import com.queuecare.scheduling.persistence.AppointmentEvent;
import com.queuecare.scheduling.persistence.AppointmentEventRepository;
import com.queuecare.scheduling.persistence.AppointmentEventType;
import com.queuecare.scheduling.persistence.AppointmentRepository;
import com.queuecare.scheduling.persistence.AppointmentStatus;
import com.queuecare.scheduling.persistence.Provider;
import com.queuecare.scheduling.persistence.ProviderRepository;
import com.queuecare.scheduling.persistence.UserAccount;
import com.queuecare.scheduling.persistence.UserAccountRepository;
import com.queuecare.scheduling.persistence.UserRole;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Remarcar (ADR 0017): MOVE o mesmo agendamento (mesma linha, mesmo id) para outro horário, mantendo prestador, serviço, cliente, pet e a
 * duração do agendamento. Só muda {@code starts_at} e {@code ends_at}; grava um evento RESCHEDULED com quem remarcou e de qual horário
 * para qual.
 * <ul>
 *   <li><b>Quem:</b> o CLIENTE dono (só se o agendamento ATUAL começa com pelo menos o prazo de cancelamento do prestador, e no máximo
 *       {@value #CUSTOMER_LIMIT} vezes por agendamento) e a EQUIPE do prestador (PROVIDER e ATTENDANT, sem prazo e sem limite: atores
 *       autenticados e auditados; a salvaguarda é o histórico, não a cota). Agendamento alheio ou inexistente: a mesma 404; atendente sem
 *       vínculo: 403 antes de qualquer consulta.</li>
 *   <li><b>Situação:</b> só SCHEDULED.</li>
 *   <li><b>Horário novo:</b> as MESMAS regras da reserva ({@link SlotRules}: R5, R1, R2), para cliente e equipe; o próprio agendamento não conta
 *       como ocupante (deslocar para um horário que sobreponha o atual é permitido).</li>
 *   <li><b>Concorrência:</b> bloqueio otimista por {@code version} (a mesma linha que check-in, cancelamento e a atualização da cópia do pet
 *       mexem: quem perde recebe CONCURRENT_UPDATE; a contagem do limite é lida na mesma transação protegida pela versão); horário disputado
 *       por outro agendamento: a restrição de exclusão do banco decide (SLOT_TAKEN, depois do rollback); a espera por bloqueios é limitada.</li>
 *   <li><b>Pet:</b> não é lido nem bloqueado: remarcar é permitido mesmo com o pet arquivado (ordem de bloqueios: cliente, pet, agendamento).</li>
 * </ul>
 */
@Service
public class RescheduleService {

	/** Remarcações permitidas a um CLIENTE por agendamento (a equipe não tem limite). */
	public static final int CUSTOMER_LIMIT = 3;

	private final UserAccountRepository users;
	private final ProviderRepository providers;
	private final AppointmentRepository appointments;
	private final AppointmentEventRepository events;
	private final StaffAccess staffAccess;
	private final SlotRules slotRules;
	private final RowLocks locks;
	private final TransactionTemplate transaction;
	private final Clock clock;

	public RescheduleService(UserAccountRepository users, ProviderRepository providers, AppointmentRepository appointments,
			AppointmentEventRepository events, StaffAccess staffAccess, SlotRules slotRules, RowLocks locks,
			PlatformTransactionManager transactionManager, Clock clock) {
		this.users = users;
		this.providers = providers;
		this.appointments = appointments;
		this.events = events;
		this.staffAccess = staffAccess;
		this.slotRules = slotRules;
		this.locks = locks;
		this.transaction = new TransactionTemplate(transactionManager);
		this.clock = clock;
	}

	/**
	 * @param localStart o novo horário LOCAL do prestador (como na reserva)
	 * @throws RescheduleRejectedException regra de remarcação violada
	 * @throws BookingRejectedException horário inválido (passado, perto demais, fora do expediente...)
	 * @throws BookingConflictException o horário novo está ocupado, inclusive quando outra requisição o ocupou ao mesmo tempo
	 */
	public Appointment reschedule(UUID appointmentId, UUID actorId, LocalDateTime localStart) {
		try {
			// A transação termina (commit ou rollback) antes de qualquer tradução de erro abaixo.
			return transaction.execute(status -> rescheduleInTransaction(appointmentId, actorId, localStart));
		} catch (OptimisticLockingFailureException e) {
			throw new RescheduleRejectedException(Reason.CONCURRENT_UPDATE);
		} catch (RuntimeException e) {
			if (SchedulingConflictDetector.isConflict(e)) {
				throw new BookingConflictException(e);
			}
			throw e;
		}
	}

	private Appointment rescheduleInTransaction(UUID appointmentId, UUID actorId, LocalDateTime localStart) {
		// Espera limitada também para a restrição de exclusão (que espera a transação de outro agendamento): estourou, 409 RESOURCE_BUSY.
		locks.limitWait();
		UserAccount actor = users.findById(actorId).filter(UserAccount::isActive).orElseThrow(AppointmentNotFoundException::new);
		boolean isStaff = actor.getRole() == UserRole.PROVIDER || actor.getRole() == UserRole.ATTENDANT;
		Optional<UUID> staffProviderId = isStaff ? staffAccess.providerIdOf(actor) : Optional.empty();
		if (actor.getRole() == UserRole.ATTENDANT && staffProviderId.isEmpty()) {
			throw new AccessDeniedException("atendente sem vínculo com um prestador");
		}

		Appointment appointment = appointments.findById(appointmentId).orElseThrow(AppointmentNotFoundException::new);
		boolean ownerCustomer = actor.getRole() == UserRole.CUSTOMER && appointment.getCustomerId().equals(actor.getId());
		boolean staffOfAppointmentProvider = staffProviderId.filter(appointment.getProviderId()::equals).isPresent();
		if (!ownerCustomer && !staffOfAppointmentProvider) {
			throw new AppointmentNotFoundException();
		}

		if (appointment.getStatus() == AppointmentStatus.CANCELLED) {
			throw new RescheduleRejectedException(Reason.ALREADY_CANCELLED);
		}
		if (appointment.getStatus() != AppointmentStatus.SCHEDULED) {
			throw new RescheduleRejectedException(Reason.NOT_RESCHEDULABLE);
		}
		Provider provider = providers.findActiveById(appointment.getProviderId())
				.orElseThrow(() -> new BookingRejectedException(BookingRejectedException.Reason.PROVIDER_NOT_FOUND));
		Instant now = clock.instant();

		if (ownerCustomer) {
			// Prazo do horário ATUAL (mesma política do cancelamento): remarcar em cima da hora seria cancelar disfarçado.
			if (!new CancellationPolicy(Duration.ofMinutes(provider.getCancellationDeadlineMinutes())).allows(appointment.getStartsAt(), now)) {
				throw new RescheduleRejectedException(Reason.TOO_LATE);
			}
			if (events.countByAppointmentIdAndEventType(appointment.getId(), AppointmentEventType.RESCHEDULED) >= CUSTOMER_LIMIT) {
				throw new RescheduleRejectedException(Reason.RESCHEDULE_LIMIT);
			}
		}

		ZoneId zone = ZoneId.of(provider.getTimeZone());
		if (appointment.getStartsAt().atZone(zone).toLocalDateTime().equals(localStart)) {
			throw new RescheduleRejectedException(Reason.NO_CHANGE);
		}
		Duration duration = Duration.between(appointment.getStartsAt(), appointment.getEndsAt());
		TimeRange range = slotRules.validate(provider, localStart, duration, now, appointment.getId());
		if (range.startsAt().equals(appointment.getStartsAt())) {
			throw new RescheduleRejectedException(Reason.NO_CHANGE);
		}

		Instant previous = appointment.getStartsAt();
		appointment.reschedule(range.startsAt(), range.endsAt());
		Appointment saved = appointments.saveAndFlush(appointment); // a restrição de exclusão e a versão decidem aqui
		events.save(AppointmentEvent.rescheduled(saved.getId(), actor.getId(), now, previous, range.startsAt()));
		return saved;
	}
}
