package com.queuecare.admin;

import com.queuecare.audit.AuditAction;
import com.queuecare.audit.AuditService;
import com.queuecare.auth.AccountProvisioner;
import com.queuecare.scheduling.application.ProviderAdminService;
import com.queuecare.scheduling.persistence.Provider;
import com.queuecare.scheduling.persistence.UserAccount;
import com.queuecare.scheduling.persistence.UserRole;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * O ADMIN cria a conta de um prestador (usuário PROVIDER + dados do negócio) de uma vez: tudo na mesma transação, junto com
 * o registro de auditoria. Falhou em qualquer parte (senha, e-mail repetido, negócio inválido): nada é gravado. A senha
 * inicial é definida pelo ADMIN e obriga a troca no primeiro acesso.
 */
@Service
public class AdminProviderService {

	private final AccountProvisioner provisioner;
	private final ProviderAdminService providerAdmin;
	private final AuditService audit;

	public AdminProviderService(AccountProvisioner provisioner, ProviderAdminService providerAdmin, AuditService audit) {
		this.provisioner = provisioner;
		this.providerAdmin = providerAdmin;
		this.audit = audit;
	}

	public record CreatedProvider(UUID userId, String name, String email, UUID providerId, String businessName) {
	}

	@Transactional
	public CreatedProvider createProvider(UUID adminUserId, String name, String email, String initialPassword, String businessName, String timeZone,
			int minimumNoticeMinutes, int cancellationDeadlineMinutes) {
		UserAccount user = provisioner.create(UserRole.PROVIDER, name, email, initialPassword, true);
		Provider provider = providerAdmin.createBusiness(user.getId(), businessName, timeZone, minimumNoticeMinutes, cancellationDeadlineMinutes);
		audit.record(adminUserId, AuditAction.PROVIDER_ACCOUNT_CREATED, user.getId(), UserRole.PROVIDER);
		return new CreatedProvider(user.getId(), user.getName(), user.getEmail(), provider.getId(), provider.getBusinessName());
	}
}
