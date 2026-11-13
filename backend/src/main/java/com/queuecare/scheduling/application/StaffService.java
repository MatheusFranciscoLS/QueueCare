package com.queuecare.scheduling.application;

import com.queuecare.auth.AuthService;
import com.queuecare.scheduling.application.ProviderExceptions.ProviderNotConfiguredException;
import com.queuecare.scheduling.application.ProviderExceptions.StaffAlreadyAssignedException;
import com.queuecare.scheduling.application.ProviderExceptions.StaffNotFoundException;
import com.queuecare.scheduling.persistence.Provider;
import com.queuecare.scheduling.persistence.ProviderRepository;
import com.queuecare.scheduling.persistence.ProviderStaff;
import com.queuecare.scheduling.persistence.ProviderStaffRepository;
import com.queuecare.scheduling.persistence.UserAccount;
import com.queuecare.scheduling.persistence.UserAccountRepository;
import com.queuecare.scheduling.persistence.UserRole;
import java.sql.SQLException;
import java.time.Clock;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Equipe do prestador (ADR 0004): vincular, listar e desvincular ATENDENTES. Só o PROVIDER dono, e SEMPRE no próprio negócio
 * (o prestador vem do usuário autenticado, nunca da requisição). O vínculo usa o id do usuário; o e-mail só serve de chave
 * de busca no momento de vincular.
 *
 * <p>Enumeração: e-mail inexistente, de quem não é atendente e de usuário inativo têm a MESMA resposta (404). Atendente já
 * vinculado (a este ou a outro prestador) é 409, sem revelar detalhes. A garantia final de "um atendente, um prestador" e
 * "só ATTENDANT" é do banco; a violação específica é traduzida, nunca qualquer erro de integridade.
 */
@Service
public class StaffService {

	private static final String UNIQUE_VIOLATION = "23505";
	private static final String FOREIGN_KEY_VIOLATION = "23503";
	private static final String UNIQUE_USER_CONSTRAINT = "provider_staff_user_uk";

	private final ProviderRepository providers;
	private final UserAccountRepository users;
	private final ProviderStaffRepository staff;
	private final Clock clock;

	public StaffService(ProviderRepository providers, UserAccountRepository users, ProviderStaffRepository staff, Clock clock) {
		this.providers = providers;
		this.users = users;
		this.staff = staff;
		this.clock = clock;
	}

	/** Atendente vinculado, para exibição: sem e-mail de terceiros além do necessário ao prestador que o vinculou. */
	public record StaffMember(UUID userId, String name, String email) {
		static StaffMember from(UserAccount user) {
			return new StaffMember(user.getId(), user.getName(), user.getEmail());
		}
	}

	@Transactional
	public StaffMember link(UUID ownerUserId, String email) {
		Provider provider = providers.findByUserId(ownerUserId).orElseThrow(ProviderNotConfiguredException::new);
		UserAccount user = users.findByEmail(AuthService.normalizeEmail(email))
				.filter(UserAccount::isActive)
				.filter(u -> u.getRole() == UserRole.ATTENDANT)
				.orElseThrow(StaffNotFoundException::new);
		if (staff.findByUserId(user.getId()).isPresent()) {
			throw new StaffAlreadyAssignedException();
		}
		try {
			staff.saveAndFlush(new ProviderStaff(provider.getId(), user.getId(), clock.instant()));
		} catch (DataIntegrityViolationException e) {
			throw translate(e);
		}
		return StaffMember.from(user);
	}

	@Transactional(readOnly = true)
	public List<StaffMember> list(UUID ownerUserId) {
		Provider provider = providers.findByUserId(ownerUserId).orElseThrow(ProviderNotConfiguredException::new);
		List<UUID> ids = staff.findByProviderId(provider.getId()).stream().map(ProviderStaff::getUserId).toList();
		Map<UUID, UserAccount> byId = users.findAllById(ids).stream().collect(Collectors.toMap(UserAccount::getId, Function.identity()));
		return ids.stream().map(byId::get).filter(java.util.Objects::nonNull).map(StaffMember::from)
				.sorted(Comparator.comparing(StaffMember::name).thenComparing(StaffMember::userId)).toList();
	}

	@Transactional
	public void unlink(UUID ownerUserId, UUID attendantUserId) {
		Provider provider = providers.findByUserId(ownerUserId).orElseThrow(ProviderNotConfiguredException::new);
		// Só apaga se o vínculo for DESTE prestador: vínculo de outro prestador responde como "não existe".
		if (staff.deleteByProviderIdAndUserId(provider.getId(), attendantUserId) == 0) {
			throw new StaffNotFoundException();
		}
	}

	/** Traduz SÓ as violações esperadas; qualquer outro erro de integridade é relançado como está. */
	private static RuntimeException translate(DataIntegrityViolationException e) {
		for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
			if (t instanceof SQLException sql) {
				if (UNIQUE_VIOLATION.equals(sql.getSQLState()) && sql.getMessage() != null && sql.getMessage().contains(UNIQUE_USER_CONSTRAINT)) {
					return new StaffAlreadyAssignedException();
				}
				if (FOREIGN_KEY_VIOLATION.equals(sql.getSQLState())) {
					// O usuário deixou de ser atendente (ou sumiu) entre a consulta e a gravação.
					return new StaffNotFoundException();
				}
			}
		}
		return e;
	}
}
