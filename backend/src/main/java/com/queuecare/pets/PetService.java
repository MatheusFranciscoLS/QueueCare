package com.queuecare.pets;

import com.queuecare.auth.RowLocks;
import com.queuecare.pets.PetExceptions.PetLimitReachedException;
import com.queuecare.pets.PetExceptions.PetNotFoundException;
import com.queuecare.scheduling.persistence.AppointmentRepository;
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
 * <p>Editar o pet atualiza, na mesma transação, a cópia dos dados nos agendamentos ainda SCHEDULED (ADR 0016, item 2).
 *
 * <p>Ordem de bloqueios (nunca invertida): cliente, depois pet, depois agendamento. Arquivar e editar bloqueiam só o pet.
 * Os bloqueios têm espera limitada (409 RESOURCE_BUSY, nunca espera infinita; veja {@link RowLocks}).
 */
@Service
public class PetService {

	public static final int MAX_ACTIVE = 20;
	public static final int MAX_TOTAL = 100;

	private final PetRepository pets;
	private final AppointmentRepository appointments;
	private final RowLocks locks;
	private final Clock clock;

	public PetService(PetRepository pets, AppointmentRepository appointments, RowLocks locks, Clock clock) {
		this.pets = pets;
		this.appointments = appointments;
		this.locks = locks;
		this.clock = clock;
	}

	@Transactional
	public Pet create(UUID customerId, PetText.Input input) {
		return takeActiveSlot(customerId, Activation.creating(PetText.normalize(input)));
	}

	@Transactional(readOnly = true)
	public List<Pet> list(UUID customerId, boolean includeArchived) {
		return pets.findOfCustomer(customerId, includeArchived);
	}

	@Transactional(readOnly = true)
	public Pet get(UUID customerId, UUID petId) {
		return pets.findByIdAndCustomerId(petId, customerId).orElseThrow(PetNotFoundException::new);
	}

	/**
	 * O pet ATIVO do cliente, ou vazio (inexistente, de outro cliente ou arquivado: sem distinção, para não dar pista). Só leitura, para quem precisa
	 * do porte e do pelo (a duração do atendimento, ADR 0020).
	 */
	@Transactional(readOnly = true)
	public java.util.Optional<Pet> findActive(UUID customerId, UUID petId) {
		return pets.findByIdAndCustomerId(petId, customerId).filter(Pet::isActive);
	}

	/** Edita os dados do pet (substituição completa). Pet arquivado também pode ser corrigido. */
	@Transactional
	public Pet update(UUID customerId, UUID petId, PetText.Input input) {
		PetText.Normalized data = PetText.normalize(input);
		Pet pet = locks.lockPet(petId, customerId).orElseThrow(PetNotFoundException::new);
		pet.update(data, clock.instant());
		Pet saved = pets.saveAndFlush(pet);
		// A cópia dos dados nos agendamentos ainda SCHEDULED acompanha a edição (o prestador vê o dado mais atual de quem ainda vai atender);
		// os demais ficam congelados. Esta transação só carrega o pet, nunca entidades Appointment.
		appointments.refreshPetSnapshot(saved.getId(), saved.getName(), saved.getSpecies(), saved.getBreed(), saved.getSize(), saved.getNotes(),
				saved.getCoat(), saved.getWeightKg(), saved.getTemperament(), saved.getHealthNotes(), saved.getVaccinated());
		return saved;
	}

	/** Anonimiza todos os pets do tutor que está excluindo a conta (ADR 0027); o chamador já bloqueou a linha do cliente. */
	@Transactional
	public int anonymizeAll(UUID customerId) {
		return pets.anonymizeAllOf(customerId, clock.instant());
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
		return takeActiveSlot(customerId, Activation.reactivating(petId));
	}

	/** O que ocupa uma vaga de pet ativo: um pet NOVO (dados normalizados) ou um pet arquivado que volta (id). Exatamente um dos dois. */
	private record Activation(PetText.Normalized newData, UUID petId) {
		static Activation creating(PetText.Normalized data) {
			return new Activation(data, null);
		}

		static Activation reactivating(UUID petId) {
			return new Activation(null, petId);
		}

		boolean createsRecord() {
			return newData != null;
		}
	}

	/**
	 * O PROTOCOLO ÚNICO de toda operação que torna um pet ativo (criar e reativar): bloquear a linha do cliente, depois a do pet (se já
	 * existe), conferir os limites e gravar, tudo na mesma transação. Nenhum outro método grava um pet ativo; um ajuste de proteção feito
	 * aqui vale para as duas operações. O teto de {@value #MAX_TOTAL} vale só para quem cria registro; o de {@value #MAX_ACTIVE}, para os dois.
	 */
	private Pet takeActiveSlot(UUID customerId, Activation activation) {
		lockCustomer(customerId);
		Pet existing = null;
		if (!activation.createsRecord()) {
			existing = locks.lockPet(activation.petId(), customerId).orElseThrow(PetNotFoundException::new);
			if (existing.isActive()) {
				return existing; // já ocupa a vaga: nada a fazer
			}
		}
		if (activation.createsRecord() && pets.countByCustomerId(customerId) >= MAX_TOTAL) {
			throw new PetLimitReachedException("limite de " + MAX_TOTAL + " pets cadastrados");
		}
		if (pets.countByCustomerIdAndActiveTrue(customerId) >= MAX_ACTIVE) {
			throw new PetLimitReachedException("limite de " + MAX_ACTIVE + " pets ativos; arquive um para "
					+ (activation.createsRecord() ? "cadastrar" : "reativar") + " outro");
		}
		Pet pet = activation.createsRecord() ? new Pet(customerId, activation.newData(), clock.instant()) : existing;
		if (!activation.createsRecord()) {
			pet.reactivate(clock.instant());
		}
		return pets.saveAndFlush(pet);
	}

	/** Bloqueia a linha do cliente (nível 1 da ordem de bloqueios). Conta inexistente, inativa ou que não é cliente: acesso negado. */
	private void lockCustomer(UUID customerId) {
		UserAccount customer = locks.lockUser(customerId).orElseThrow(() -> new AccessDeniedException("cliente inválido"));
		if (!customer.isActive() || customer.getRole() != UserRole.CUSTOMER) {
			throw new AccessDeniedException("cliente inválido");
		}
	}
}
