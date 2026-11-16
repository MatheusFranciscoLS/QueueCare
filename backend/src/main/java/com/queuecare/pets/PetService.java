package com.queuecare.pets;

import com.queuecare.auth.RowLocks;
import com.queuecare.pets.PetExceptions.PetLimitReachedException;
import com.queuecare.pets.PetExceptions.PetNotFoundException;
import com.queuecare.scheduling.persistence.UserAccount;
import com.queuecare.scheduling.persistence.UserRole;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cadastro de pets do tutor (ADR 0016). O dono vem SEMPRE do usuário autenticado, nunca da requisição; pet de outro cliente é
 * indistinguível de pet inexistente (404).
 *
 * <p>Limites: {@value #MAX_ACTIVE} pets ativos e {@value #MAX_TOTAL} no total (ativos + arquivados). Criar confere os dois;
 * reativar confere só o de ativos (não cria registro). Toda operação que torna um pet ativo (criar, reativar) segue o MESMO protocolo,
 * na mesma transação: bloquear a linha do CLIENTE, contar, gravar. Este serviço é o único lugar que escreve em {@code pets} por
 * esses caminhos; sem o bloqueio, dois pedidos simultâneos leriam a mesma contagem e passariam do limite.
 *
 * <p>Ordem de bloqueios (nunca invertida): cliente, depois pet, depois agendamento. Arquivar e editar bloqueiam só o pet.
 * Os bloqueios têm espera limitada (409 RESOURCE_BUSY, nunca espera infinita; veja {@link RowLocks}).
 */
@Service
public class PetService {

	public static final int MAX_ACTIVE = 20;
	public static final int MAX_TOTAL = 100;

	private final PetRepository pets;
	private final RowLocks locks;
	private final Clock clock;

	public PetService(PetRepository pets, RowLocks locks, Clock clock) {
		this.pets = pets;
		this.locks = locks;
		this.clock = clock;
	}

	@Transactional
	public Pet create(UUID customerId, PetText.Input input) {
		PetText.Normalized data = PetText.normalize(input);
		lockCustomer(customerId);
		if (pets.countByCustomerId(customerId) >= MAX_TOTAL) {
			throw new PetLimitReachedException("limite de " + MAX_TOTAL + " pets cadastrados");
		}
		if (pets.countByCustomerIdAndActiveTrue(customerId) >= MAX_ACTIVE) {
			throw new PetLimitReachedException("limite de " + MAX_ACTIVE + " pets ativos; arquive um para cadastrar outro");
		}
		return pets.saveAndFlush(new Pet(customerId, data, clock.instant()));
	}

	@Transactional(readOnly = true)
	public List<Pet> list(UUID customerId, boolean includeArchived) {
		return pets.findOfCustomer(customerId, includeArchived);
	}

	@Transactional(readOnly = true)
	public Pet get(UUID customerId, UUID petId) {
		return pets.findByIdAndCustomerId(petId, customerId).orElseThrow(PetNotFoundException::new);
	}

	/** Edita os dados do pet (substituição completa). Pet arquivado também pode ser corrigido. */
	@Transactional
	public Pet update(UUID customerId, UUID petId, PetText.Input input) {
		PetText.Normalized data = PetText.normalize(input);
		Pet pet = locks.lockPet(petId, customerId).orElseThrow(PetNotFoundException::new);
		pet.update(data, clock.instant());
		return pets.saveAndFlush(pet);
	}

	/** Arquiva (não apaga). Repetir é inofensivo. Agendamento futuro continua válido; só novas reservas passam a ser recusadas. */
	@Transactional
	public Pet archive(UUID customerId, UUID petId) {
		Pet pet = locks.lockPet(petId, customerId).orElseThrow(PetNotFoundException::new);
		if (pet.isActive()) {
			pet.archive(clock.instant());
			pets.saveAndFlush(pet);
		}
		return pet;
	}

	/** Reativa um pet arquivado. Só o limite de ativos vale (o total não muda). Repetir em pet já ativo é inofensivo. */
	@Transactional
	public Pet reactivate(UUID customerId, UUID petId) {
		lockCustomer(customerId);
		Pet pet = locks.lockPet(petId, customerId).orElseThrow(PetNotFoundException::new);
		if (!pet.isActive()) {
			if (pets.countByCustomerIdAndActiveTrue(customerId) >= MAX_ACTIVE) {
				throw new PetLimitReachedException("limite de " + MAX_ACTIVE + " pets ativos; arquive um para reativar outro");
			}
			pet.reactivate(clock.instant());
			pets.saveAndFlush(pet);
		}
		return pet;
	}

	/** Bloqueia a linha do cliente (nível 1 da ordem de bloqueios). Conta inexistente, inativa ou que não é cliente: acesso negado. */
	private void lockCustomer(UUID customerId) {
		UserAccount customer = locks.lockUser(customerId).orElseThrow(() -> new AccessDeniedException("cliente inválido"));
		if (!customer.isActive() || customer.getRole() != UserRole.CUSTOMER) {
			throw new AccessDeniedException("cliente inválido");
		}
	}
}
