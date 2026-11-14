package com.queuecare.auth;

import com.queuecare.audit.AuditAction;
import com.queuecare.audit.AuditService;
import com.queuecare.scheduling.persistence.UserAccount;
import com.queuecare.scheduling.persistence.UserAccountRepository;
import com.queuecare.scheduling.persistence.UserRole;
import jakarta.persistence.EntityManager;
import java.nio.charset.StandardCharsets;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cria o PRIMEIRO administrador, uma única vez, a partir de um comando explícito (ver {@link AdminBootstrapRunner}).
 *
 * <ul>
 *   <li>Nenhuma migration insere usuário: sem o comando, não existe ADMIN.</li>
 *   <li>Idempotente: se já existe QUALQUER ADMIN, não faz nada. Nunca reativa, nunca troca senha nem e-mail de um ADMIN existente.</li>
 *   <li>Serializado no banco por um bloqueio consultivo da transação: dois comandos simultâneos criam no máximo um ADMIN.</li>
 *   <li>Entrada inválida falha SEM repetir os valores (senha e e-mail nunca vão para mensagem nem log).</li>
 *   <li>Política mais rígida que a dos demais perfis: senha de pelo menos 12 caracteres.</li>
 * </ul>
 */
@Service
public class AdminBootstrapService {

	public enum Result {
		CREATED, ALREADY_EXISTS
	}

	/** Erro de configuração do comando; a mensagem NUNCA contém o e-mail nem a senha informados. */
	public static final class BootstrapException extends RuntimeException {
		BootstrapException(String message) {
			super(message);
		}
	}

	/** Regra de formato de e-mail compartilhada com os DTOs de criação de conta (as mesmas anotações). */
	static final class EmailInput {
		@jakarta.validation.constraints.Email
		@jakarta.validation.constraints.Size(max = 254)
		String value;
	}

	private static final jakarta.validation.Validator VALIDATOR = jakarta.validation.Validation.buildDefaultValidatorFactory().getValidator();

	static final int MIN_ADMIN_PASSWORD_CHARACTERS = 12;
	/** Chave do bloqueio consultivo que serializa o bootstrap (constante arbitrária, exclusiva deste uso). */
	private static final long BOOTSTRAP_LOCK_KEY = 7_201_100_001L;
	private static final String ADMIN_NAME = "Administrador";

	private final UserAccountRepository users;
	private final AccountProvisioner provisioner;
	private final AuditService audit;
	private final EntityManager entityManager;

	public AdminBootstrapService(UserAccountRepository users, AccountProvisioner provisioner, AuditService audit, EntityManager entityManager) {
		this.users = users;
		this.provisioner = provisioner;
		this.audit = audit;
		this.entityManager = entityManager;
	}

	@Transactional
	public Result bootstrap(String email, String password) {
		entityManager.createNativeQuery("select pg_advisory_xact_lock(?1)").setParameter(1, BOOTSTRAP_LOCK_KEY).getResultList();
		// Idempotência ANTES de validar a entrada: com um ADMIN já criado, o comando executado de novo SEM as variáveis (que o
		// operador removeu) termina com sucesso e nada muda; só se valida a entrada quando ainda há o que criar.
		if (users.existsByRole(UserRole.ADMIN)) {
			return Result.ALREADY_EXISTS;
		}
		validate(email, password);
		if (users.findByEmail(AuthService.normalizeEmail(email)).isPresent()) {
			throw new BootstrapException("o e-mail informado já pertence a uma conta que não é ADMIN; use outro e-mail");
		}
		UserAccount admin = provisioner.provision(UserRole.ADMIN, ADMIN_NAME, email, password, false);
		audit.record(admin.getId(), AuditAction.ADMIN_BOOTSTRAPPED, admin.getId(), UserRole.ADMIN);
		return Result.CREATED;
	}

	private static void validate(String email, String password) {
		if (email == null || email.isBlank() || password == null || password.isEmpty()) {
			throw new BootstrapException("ADMIN_EMAIL e ADMIN_PASSWORD são obrigatórios");
		}
		// A MESMA regra de formato dos DTOs de criação de conta (@Email e tamanho máximo), não uma expressão improvisada.
		if (!VALIDATOR.validateValue(EmailInput.class, "value", email.trim()).isEmpty()) {
			throw new BootstrapException("ADMIN_EMAIL inválido");
		}
		if (password.codePointCount(0, password.length()) < MIN_ADMIN_PASSWORD_CHARACTERS
				|| password.getBytes(StandardCharsets.UTF_8).length > AuthService.MAX_PASSWORD_BYTES) {
			throw new BootstrapException("ADMIN_PASSWORD fora da política (mínimo " + MIN_ADMIN_PASSWORD_CHARACTERS + " caracteres e no máximo "
					+ AuthService.MAX_PASSWORD_BYTES + " bytes em UTF-8)");
		}
	}
}
