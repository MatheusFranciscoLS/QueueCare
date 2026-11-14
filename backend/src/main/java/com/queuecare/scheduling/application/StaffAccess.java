package com.queuecare.scheduling.application;

import com.queuecare.scheduling.persistence.Provider;
import com.queuecare.scheduling.persistence.ProviderRepository;
import com.queuecare.scheduling.persistence.ProviderStaff;
import com.queuecare.scheduling.persistence.ProviderStaffRepository;
import com.queuecare.scheduling.persistence.UserAccount;
import com.queuecare.scheduling.persistence.UserAccountRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * PONTO ÚNICO de confiança para "de qual prestador este usuário opera?" (ADR 0004). Toda operação de equipe
 * (cancelar como prestador/atendente, ver a agenda) obtém o prestador AQUI e nunca de parâmetro, corpo ou token.
 *
 * <p>Regras:
 * <ul>
 *   <li>PROVIDER: o prestador cujo {@code user_id} é o do usuário (o negócio dele);</li>
 *   <li>ATTENDANT: o prestador ao qual o vínculo atual em {@code provider_staff} o associa (no máximo um);</li>
 *   <li>CUSTOMER, usuário inativo ou sem vínculo: vazio.</li>
 * </ul>
 * O perfil e a situação (ativo) vêm do {@link UserAccount} carregado do BANCO, e o vínculo é consultado no banco A CADA
 * CHAMADA: sem cache e sem o prestador dentro do JWT, para que desvincular, desativar ou trocar de prestador valha na hora.
 * Um resultado vazio nunca vira prestador padrão; cada caso de uso decide a resposta (403, 404...).
 */
@Component
public class StaffAccess {

	private final UserAccountRepository users;
	private final ProviderRepository providers;
	private final ProviderStaffRepository staff;

	public StaffAccess(UserAccountRepository users, ProviderRepository providers, ProviderStaffRepository staff) {
		this.users = users;
		this.providers = providers;
		this.staff = staff;
	}

	/** Prestador em que o usuário (já carregado do banco) opera; vazio se não opera em nenhum. */
	public Optional<UUID> providerIdOf(UserAccount user) {
		if (!user.isActive()) {
			return Optional.empty();
		}
		return switch (user.getRole()) {
			case PROVIDER -> providers.findByUserId(user.getId()).map(Provider::getId);
			case ATTENDANT -> staff.findByUserId(user.getId()).map(ProviderStaff::getProviderId);
			case ADMIN, CUSTOMER -> Optional.empty();
		};
	}

	/** Mesma resolução a partir do id do usuário (carrega o usuário do banco). */
	public Optional<UUID> providerIdOf(UUID userId) {
		return users.findById(userId).flatMap(this::providerIdOf);
	}
}
