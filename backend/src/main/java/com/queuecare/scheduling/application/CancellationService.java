package com.queuecare.scheduling.application;

import com.queuecare.scheduling.application.AppointmentExceptions.AppointmentNotFoundException;
import com.queuecare.scheduling.application.AppointmentExceptions.CancellationRejectedException;
import com.queuecare.scheduling.application.AppointmentExceptions.CancellationRejectedException.Reason;
import com.queuecare.scheduling.domain.CancellationPolicy;
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
import java.util.UUID;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Cancelamento (R3) com autorização por dono (R4).
 * <ul>
 *   <li>CUSTOMER cancela só os PRÓPRIOS agendamentos e dentro do prazo do prestador;</li>
 *   <li>PROVIDER cancela os agendamentos do PRÓPRIO negócio, sem prazo;</li>
 *   <li>ATTENDANT ainda não está ligado a um prestador no modelo de dados: acesso negado (403) até haver esse vínculo;</li>
 *   <li>agendamento de outra pessoa ou inexistente: a mesma resposta (404), para não revelar que ele existe.</li>
 * </ul>
 * O cancelamento grava o evento CANCELLED e libera o horário (a restrição do banco ignora cancelados). Dois cancelamentos
 * simultâneos do mesmo agendamento: um vence e o outro recebe conflito (bloqueio otimista por {@code version}).
 */
@Service
public class CancellationService {

	private final UserAccountRepository users;
	private final ProviderRepository providers;
	private final AppointmentRepository appointments;
	private final AppointmentEventRepository events;
	private final TransactionTemplate transaction;
	private final Clock clock;

	public CancellationService(UserAccountRepository users, ProviderRepository providers, AppointmentRepository appointments,
			AppointmentEventRepository events, PlatformTransactionManager transactionManager, Clock clock) {
		this.users = users;
		this.providers = providers;
		this.appointments = appointments;
		this.events = events;
		this.transaction = new TransactionTemplate(transactionManager);
		this.clock = clock;
	}

	public Appointment cancel(UUID appointmentId, UUID actorId) {
		try {
			return transaction.execute(status -> cancelInTransaction(appointmentId, actorId));
		} catch (OptimisticLockingFailureException e) {
			// Depois do rollback: outra requisição alterou o mesmo agendamento ao mesmo tempo.
			throw new CancellationRejectedException(Reason.CONCURRENT_UPDATE);
		}
	}

	private Appointment cancelInTransaction(UUID appointmentId, UUID actorId) {
		UserAccount actor = users.findById(actorId).filter(UserAccount::isActive).orElseThrow(AppointmentNotFoundException::new);
		if (actor.getRole() == UserRole.ATTENDANT) {
			throw new AccessDeniedException("atendente ainda não está vinculado a um prestador");
		}
		Appointment appointment = appointments.findById(appointmentId).orElseThrow(AppointmentNotFoundException::new);
		Provider provider = providers.findById(appointment.getProviderId()).orElseThrow(AppointmentNotFoundException::new);

		boolean ownerCustomer = actor.getRole() == UserRole.CUSTOMER && appointment.getCustomerId().equals(actor.getId());
		boolean ownerProvider = actor.getRole() == UserRole.PROVIDER && provider.getUserId().equals(actor.getId());
		if (!ownerCustomer && !ownerProvider) {
			throw new AppointmentNotFoundException();
		}

		if (appointment.getStatus() == AppointmentStatus.CANCELLED) {
			throw new CancellationRejectedException(Reason.ALREADY_CANCELLED);
		}
		if (appointment.getStatus() != AppointmentStatus.SCHEDULED) {
			throw new CancellationRejectedException(Reason.NOT_CANCELLABLE);
		}
		Instant now = clock.instant();
		if (ownerCustomer && !new CancellationPolicy(Duration.ofMinutes(provider.getCancellationDeadlineMinutes()))
				.allows(appointment.getStartsAt(), now)) {
			throw new CancellationRejectedException(Reason.TOO_LATE);
		}

		appointment.cancel();
		Appointment saved = appointments.saveAndFlush(appointment);
		events.save(new AppointmentEvent(saved.getId(), actor.getId(), AppointmentEventType.CANCELLED, now, null));
		return saved;
	}
}
