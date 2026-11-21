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
import java.util.EnumSet;
import java.util.Set;
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
 *   <li>começar (IN_PROGRESS), pronto (READY) e conclusão (COMPLETED): só para frente, a partir de quem já chegou (ADR 0019);</li>
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
		return run(() -> inTransaction(appointmentId, actorId, EnumSet.of(AppointmentStatus.SCHEDULED), AppointmentEventType.CHECKED_IN, Appointment::checkIn, true));
	}

	/**
	 * Faltou (ADR 0021): só de quem estava SCHEDULED e só a partir do horário marcado (antes disso, TOO_EARLY): ninguém falta a um horário que ainda não chegou.
	 * Estado final; o agendamento continua ocupando o horário no banco (já passou ou está passando).
	 */
	public Appointment noShow(UUID appointmentId, UUID actorId) {
		return run(() -> inTransaction(appointmentId, actorId, EnumSet.of(AppointmentStatus.SCHEDULED), AppointmentEventType.NO_SHOW, Appointment::markNoShow, false));
	}

	/**
	 * Lembrete enviado (ADR 0021): a equipe abriu o WhatsApp com a mensagem pronta e o agendamento guarda quando. Só de quem ainda está SCHEDULED e
	 * cujo horário não passou (depois disso lembrar não faz sentido: INVALID_STATE). Sem evento no histórico: pode se repetir.
	 */
	public Appointment remind(UUID appointmentId, UUID actorId) {
		return run(() -> {
			UserAccount actor = users.findById(actorId).filter(UserAccount::isActive).orElseThrow(() -> new AccessDeniedException("usuário inválido"));
			if (actor.getRole() != UserRole.PROVIDER && actor.getRole() != UserRole.ATTENDANT) {
				throw new AccessDeniedException("perfil sem acesso ao atendimento");
			}
			UUID providerId = staffAccess.providerIdOf(actor).orElseThrow(() -> new AccessDeniedException("sem vínculo com um prestador"));
			Appointment appointment = appointments.findById(appointmentId).filter(a -> a.getProviderId().equals(providerId)).orElseThrow(AppointmentNotFoundException::new);
			if (appointment.getStatus() == AppointmentStatus.CANCELLED) {
				throw new AttendanceRejectedException(Reason.ALREADY_CANCELLED);
			}
			Instant now = clock.instant();
			if (appointment.getStatus() != AppointmentStatus.SCHEDULED || !now.isBefore(appointment.getStartsAt())) {
				throw new AttendanceRejectedException(Reason.INVALID_STATE);
			}
			appointment.markReminded(now);
			return appointments.saveAndFlush(appointment);
		});
	}

	/**
	 * Sinal recebido (ADR 0023): a equipe confirma que o Pix do sinal chegou. Só de agendamento com sinal PEDIDO e que não foi cancelado; marcar de novo
	 * ou sem sinal pedido é INVALID_STATE. Não muda a situação do atendimento e não cancela nada sozinho.
	 */
	public Appointment depositReceived(UUID appointmentId, UUID actorId) {
		return run(() -> {
			UserAccount actor = users.findById(actorId).filter(UserAccount::isActive).orElseThrow(() -> new AccessDeniedException("usuário inválido"));
			if (actor.getRole() != UserRole.PROVIDER && actor.getRole() != UserRole.ATTENDANT) {
				throw new AccessDeniedException("perfil sem acesso ao atendimento");
			}
			UUID providerId = staffAccess.providerIdOf(actor).orElseThrow(() -> new AccessDeniedException("sem vínculo com um prestador"));
			Appointment appointment = appointments.findById(appointmentId).filter(a -> a.getProviderId().equals(providerId)).orElseThrow(AppointmentNotFoundException::new);
			if (appointment.getStatus() == AppointmentStatus.CANCELLED) {
				throw new AttendanceRejectedException(Reason.ALREADY_CANCELLED);
			}
			if (appointment.getDepositStatus() != com.queuecare.scheduling.persistence.DepositStatus.REQUESTED) {
				throw new AttendanceRejectedException(Reason.INVALID_STATE);
			}
			appointment.markDepositReceived(clock.instant());
			return appointments.saveAndFlush(appointment);
		});
	}

	/**
	 * Desfazer a última etapa (ADR 0026): quem tocou sem querer em "Chegou", "Começar" ou "Pronto" volta UMA etapa (pronto para em atendimento ou chegou; em atendimento
	 * para chegou; chegou para agendado). Só de atendimento EM ANDAMENTO: concluído, faltou e cancelado são finais (INVALID_STATE / ALREADY_CANCELLED). O evento da etapa
	 * desfeita sai (a etapa pode ser refeita; quem chega de novo vai para o fim da fila) e entra um UNDONE com quem desfez e qual etapa era.
	 */
	public Appointment undo(UUID appointmentId, UUID actorId) {
		return run(() -> {
			UserAccount actor = users.findById(actorId).filter(UserAccount::isActive).orElseThrow(() -> new AccessDeniedException("usuário inválido"));
			if (actor.getRole() != UserRole.PROVIDER && actor.getRole() != UserRole.ATTENDANT) {
				throw new AccessDeniedException("perfil sem acesso ao atendimento");
			}
			UUID providerId = staffAccess.providerIdOf(actor).orElseThrow(() -> new AccessDeniedException("sem vínculo com um prestador"));
			Appointment appointment = appointments.findById(appointmentId).filter(a -> a.getProviderId().equals(providerId)).orElseThrow(AppointmentNotFoundException::new);
			if (appointment.getStatus() == AppointmentStatus.CANCELLED) {
				throw new AttendanceRejectedException(Reason.ALREADY_CANCELLED);
			}
			AppointmentEventType undone = switch (appointment.getStatus()) {
				case CHECKED_IN -> AppointmentEventType.CHECKED_IN;
				case IN_PROGRESS -> AppointmentEventType.STARTED;
				case READY -> AppointmentEventType.READY;
				default -> throw new AttendanceRejectedException(Reason.INVALID_STATE);
			};
			events.deleteByAppointmentIdAndEventType(appointment.getId(), undone);
			Set<AppointmentEventType> remaining = events.findByAppointmentId(appointment.getId()).stream().map(AppointmentEvent::getEventType)
					.collect(java.util.stream.Collectors.toSet());
			AppointmentStatus previous = remaining.contains(AppointmentEventType.STARTED) ? AppointmentStatus.IN_PROGRESS
					: remaining.contains(AppointmentEventType.CHECKED_IN) ? AppointmentStatus.CHECKED_IN : AppointmentStatus.SCHEDULED;
			appointment.stepBackTo(previous);
			Appointment saved = appointments.saveAndFlush(appointment);
			events.save(new AppointmentEvent(saved.getId(), actor.getId(), AppointmentEventType.UNDONE, clock.instant(), undone.name()));
			return saved;
		});
	}

	/** Começa o atendimento (ADR 0019): só de quem chegou. */
	public Appointment start(UUID appointmentId, UUID actorId) {
		return run(() -> inTransaction(appointmentId, actorId, EnumSet.of(AppointmentStatus.CHECKED_IN), AppointmentEventType.STARTED, Appointment::start, false));
	}

	/** Pet pronto para retirada (ADR 0019): de quem chegou ou está em atendimento (começar não é obrigatório). */
	public Appointment ready(UUID appointmentId, UUID actorId) {
		return run(() -> inTransaction(appointmentId, actorId, EnumSet.of(AppointmentStatus.CHECKED_IN, AppointmentStatus.IN_PROGRESS), AppointmentEventType.READY,
				Appointment::markReady, false));
	}

	/** Conclui (pet retirado): de qualquer situação de atendimento em andamento; só para frente, nunca de SCHEDULED nem de CANCELLED. */
	public Appointment complete(UUID appointmentId, UUID actorId) {
		return run(() -> inTransaction(appointmentId, actorId, EnumSet.of(AppointmentStatus.CHECKED_IN, AppointmentStatus.IN_PROGRESS, AppointmentStatus.READY),
				AppointmentEventType.COMPLETED, Appointment::complete, false));
	}

	private Appointment run(java.util.function.Supplier<Appointment> action) {
		try {
			return transaction.execute(status -> action.get());
		} catch (OptimisticLockingFailureException e) {
			// Depois do rollback: outra requisição alterou o mesmo agendamento ao mesmo tempo.
			throw new AttendanceRejectedException(Reason.CONCURRENT_UPDATE);
		}
	}

	private Appointment inTransaction(UUID appointmentId, UUID actorId, Set<AppointmentStatus> allowed, AppointmentEventType event,
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
		if (!allowed.contains(appointment.getStatus())) {
			throw new AttendanceRejectedException(Reason.INVALID_STATE);
		}
		Instant now = clock.instant();
		if (event == AppointmentEventType.NO_SHOW && now.isBefore(appointment.getStartsAt())) {
			throw new AttendanceRejectedException(Reason.TOO_EARLY);
		}
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
