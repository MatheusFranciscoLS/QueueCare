package com.queuecare.admin;

import com.queuecare.admin.AdminExceptions.CannotDeactivateSelfException;
import com.queuecare.admin.AdminExceptions.LastActiveAdminException;
import com.queuecare.admin.AdminExceptions.UserNotFoundException;
import com.queuecare.audit.AuditAction;
import com.queuecare.audit.AuditService;
import com.queuecare.auth.RowLocks;
import com.queuecare.scheduling.persistence.UserAccount;
import com.queuecare.scheduling.persistence.UserAccountRepository;
import com.queuecare.scheduling.persistence.UserRole;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * O ADMIN ativa e desativa contas (ADR 0007). Regras:
 * <ul>
 *   <li>não desativa a PRÓPRIA conta (409) e nunca deixa o sistema sem ADMIN ativo (409);</li>
 *   <li>a decisão é tomada SOB a linha de controle de administração, depois de reconferir que o ator ainda é um ADMIN ativo: dois
 *       ADMIN que se desativam ao mesmo tempo são atendidos um de cada vez (o segundo já não é ativo e é recusado);</li>
 *   <li>desativar e reativar sobem a versão das credenciais: nenhum token emitido antes volta a valer;</li>
 *   <li>repetir a operação (já ativa/inativa) não muda nada nem audita de novo (204);</li>
 *   <li>não há exclusão de usuário pela API (o banco também recusa apagar usuário com histórico).</li>
 * </ul>
 */
@Service
public class AdminUserService {

	private final UserAccountRepository users;
	private final RowLocks locks;
	private final AuditService audit;

	public AdminUserService(UserAccountRepository users, RowLocks locks, AuditService audit) {
		this.users = users;
		this.locks = locks;
		this.audit = audit;
	}

	@Transactional
	public void deactivate(UUID adminUserId, UUID targetUserId) {
		if (adminUserId.equals(targetUserId)) {
			throw new CannotDeactivateSelfException();
		}
		UserAccount target = lockActorAndTarget(adminUserId, targetUserId);
		if (!target.isActive()) {
			return;
		}
		if (target.getRole() == UserRole.ADMIN && users.countByRoleAndActiveTrue(UserRole.ADMIN) <= 1) {
			throw new LastActiveAdminException();
		}
		target.setActive(false);
		users.saveAndFlush(target);
		audit.record(adminUserId, AuditAction.USER_DEACTIVATED, target.getId(), target.getRole());
	}

	@Transactional
	public void activate(UUID adminUserId, UUID targetUserId) {
		UserAccount target = lockActorAndTarget(adminUserId, targetUserId);
		if (target.isActive()) {
			return;
		}
		target.setActive(true);
		users.saveAndFlush(target);
		audit.record(adminUserId, AuditAction.USER_ACTIVATED, target.getId(), target.getRole());
	}

	/** Ordem fixa: linha de controle, depois (reconferido) o ator, depois o alvo. */
	private UserAccount lockActorAndTarget(UUID adminUserId, UUID targetUserId) {
		locks.lockAdminControl();
		boolean actorStillAdmin = users.findById(adminUserId).filter(UserAccount::isActive).filter(u -> u.getRole() == UserRole.ADMIN).isPresent();
		if (!actorStillAdmin) {
			throw new AccessDeniedException("administrador inativo");
		}
		return locks.lockUser(targetUserId).orElseThrow(UserNotFoundException::new);
	}
}
