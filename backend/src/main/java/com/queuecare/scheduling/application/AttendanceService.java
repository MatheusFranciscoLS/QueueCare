package com.queuecare.scheduling.application;

import com.queuecare.scheduling.application.AppointmentExceptions.AppointmentNotFoundException;
import com.queuecare.scheduling.application.AppointmentExceptions.AttendanceRejectedException;
import com.queuecare.scheduling.application.AppointmentExceptions.AttendanceRejectedException.Reason;
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
import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;
import java.util.function.Consumer;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Chegada e conclusão do atendimento (R6). Só a EQUIPE do prestador (PROVIDER dono e ATTENDANT vinculado) opera, e o
 * prestador vem SEMPRE de {@link StaffAccess} (banco a cada chamada). Agendamento de outro prestador ou inexistente: 404 (a mesma
 * resposta, sem pista de existência); atendente sem vínculo: 403 antes de qualquer consulta ao agendamento.
 * <ul>
 *   <li>check-in: SCHEDULED para CHECKED_IN, só no DIA do agendamento no fuso do prestador (WRONG_DAY, 422);</li>
 *   <li>conclusão: CHECKED_IN para COMPLETED;</li>
 *   <li>cancelado: ALREADY_CANCELLED; qualquer outra situação: INVALID_STATE (409);</li>
 *   <li>cada transição grava o evento no histórico; duas requisições ao mesmo tempo: uma vence e a outra recebe
 *       CONCURRENT_UPDATE (bloqueio otimista por {@code version}).</li>
 * </ul>
 */
@Service
public class AttendanceService {

	private final UserAccountRepository users;
	private final ProviderRepository providers;
	private final AppointmentRepository appointments;
	private final AppointmentEventRepository events;
	private final StaffAccess staffAccess;
	private final TransactionTemplate transaction;
	private final Clock clock;

	public AttendanceService(UserAccountRepository users, ProviderRepository providers, AppointmentRepository appointments,
			AppointmentEventRepository events, StaffAccess staffAccess, PlatformTransactionManager transactionManager, Clock clock) {
		this.users = users;
		this.providers = providers;
		this.appointments = appointments;
		this.events = events;
		this.staffAccess = staffAccess;
		this.transaction = new TransactionTemplate(transactionManager);
		this.clock = clock;
	}

	public Appointment checkIn(UUID appointmentId, UUID actorId) {
		return run(() -> inTransaction(appointmentId, actorId, AppointmentStatus.SCHEDULED, AppointmentEventType.CHECKED_IN, Appointment::checkIn, true));
	}

	public Appointment complete(UUID appointmentId, UUID actorId) {
		return run(() -> inTransaction(appointmentId, actorId, AppointmentStatus.CHECKED_IN, AppointmentEventType.COMPLETED, Appointment::complete, false));
	}

	private Appointment run(java.util.function.Supplier<Appointment> action) {
		try {
			return transaction.execute(status -> action.get());
		} catch (OptimisticLockingFailureException e) {
			// Depois do rollback: outra requisição alterou o mesmo agendamento ao mesmo tempo.
			throw new AttendanceRejectedException(Reason.CONCURRENT_UPDATE);
		}
	}

	private Appointment inTransaction(UUID appointmentId, UUID actorId, AppointmentStatus required, AppointmentEventType event,
			Consumer<Appointment> transition, boolean sameDayOnly) {
		UserAccount actor = users.findById(actorId).filter(UserAccount::isActive).orElseThrow(() -> new AccessDeniedException("usuário inválido"));
		if (actor.getRole() != UserRole.PROVIDER && actor.getRole() != UserRole.ATTENDANT) {
			throw new AccessDeniedException("perfil sem acesso ao atendimento");
		}
		UUID providerId = staffAccess.providerIdOf(actor).orElseThrow(() -> new AccessDeniedException("sem vínculo com um prestador"));

		Appointment appointment = appointments.findById(appointmentId).filter(a -> a.getProviderId().equals(providerId)).orElseThrow(AppointmentNotFoundException::new);
		if (appointment.getStatus() == AppointmentStatus.CANCELLED) {
			throw new AttendanceRejectedException(Reason.ALREADY_CANCELLED);
		}
		if (appointment.getStatus() != required) {
			throw new AttendanceRejectedException(Reason.INVALID_STATE);
		}
		Instant now = clock.instant();
		if (sameDayOnly) {
			Provider provider = providers.findById(providerId).orElseThrow(AppointmentNotFoundException::new);
			ZoneId zone = ZoneId.of(provider.getTimeZone());
			if (!appointment.getStartsAt().atZone(zone).toLocalDate().equals(now.atZone(zone).toLocalDate())) {
				throw new AttendanceRejectedException(Reason.WRONG_DAY);
			}
		}

		transition.accept(appointment);
		Appointment saved = appointments.saveAndFlush(appointment);
		events.save(new AppointmentEvent(saved.getId(), actor.getId(), event, now, null));
		return saved;
	}
}
