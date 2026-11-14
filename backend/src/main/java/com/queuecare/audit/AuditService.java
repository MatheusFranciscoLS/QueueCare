package com.queuecare.audit;

import com.queuecare.scheduling.persistence.UserRole;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Grava a auditoria NA MESMA transação da operação auditada ({@code MANDATORY}: sem transação em andamento é erro de
 * programação). Assim, ou a operação e o registro acontecem juntos, ou nenhum dos dois.
 */
@Service
public class AuditService {

	private final AuditLogRepository repository;
	private final Clock clock;

	public AuditService(AuditLogRepository repository, Clock clock) {
		this.repository = repository;
		this.clock = clock;
	}

	@Transactional(propagation = Propagation.MANDATORY)
	public void record(UUID actorUserId, AuditAction action, UUID targetUserId, UserRole targetRole) {
		repository.saveAndFlush(new AuditLog(clock.instant(), actorUserId, action, targetUserId, targetRole));
	}
}
