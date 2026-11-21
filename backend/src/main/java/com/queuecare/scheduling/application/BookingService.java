package com.queuecare.scheduling.application;

import com.queuecare.auth.RowLocks;
import com.queuecare.pets.Pet;
import com.queuecare.scheduling.application.BookingRejectedException.Reason;
import com.queuecare.scheduling.domain.TimeRange;
import com.queuecare.scheduling.persistence.Appointment;
import com.queuecare.scheduling.persistence.AppointmentEvent;
import com.queuecare.scheduling.persistence.AppointmentEventRepository;
import com.queuecare.scheduling.persistence.AppointmentEventType;
import com.queuecare.scheduling.persistence.AppointmentRepository;
import com.queuecare.scheduling.persistence.Provider;
import com.queuecare.scheduling.persistence.ProviderRepository;
import com.queuecare.scheduling.persistence.ServiceOffering;
import com.queuecare.scheduling.persistence.ServiceOfferingRepository;
import com.queuecare.scheduling.persistence.UserAccount;
import com.queuecare.scheduling.persistence.UserAccountRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Caso de uso "agendar": junta as regras puras (R5 horário local e antecedência, R1 disponibilidade, R2 sobreposição)
 * com a persistência.
 *
 * <p>Fluxo: converter o horário local (R5) -> calcular o fim pela duração do serviço -> antecedência e passado (R5) ->
 * expediente (R1) -> checagem de sobreposição para dar mensagem clara (R2, camada da aplicação) -> gravar.
 * A checagem prévia NÃO impede reserva dupla sob concorrência; quem garante é a restrição de exclusão do banco (ADR 0002).
 * O erro do banco é traduzido para {@link BookingConflictException} DEPOIS do rollback da transação que falhou
 * (no PostgreSQL ela fica abortada), e só quando for o conflito de horário (23P01 da restrição ou 40P01), nunca para
 * outros erros de integridade.
 */
@Service
public class BookingService {

	private final ProviderRepository providers;
	private final ServiceOfferingRepository services;
	private final UserAccountRepository users;
	private final SlotRules slotRules;
	private final AppointmentRepository appointments;
	private final AppointmentEventRepository events;
	private final RowLocks locks;
	private final DepositPolicy depositPolicy;
	private final TransactionTemplate transaction;
	private final Clock clock;

	public BookingService(ProviderRepository providers, ServiceOfferingRepository services, UserAccountRepository users,
			SlotRules slotRules, AppointmentRepository appointments, AppointmentEventRepository events,
			RowLocks locks, DepositPolicy depositPolicy, PlatformTransactionManager transactionManager, Clock clock) {
		this.providers = providers;
		this.services = services;
		this.users = users;
		this.slotRules = slotRules;
		this.appointments = appointments;
		this.events = events;
		this.locks = locks;
		this.depositPolicy = depositPolicy;
		this.transaction = new TransactionTemplate(transactionManager);
		this.clock = clock;
	}

	/**
	 * Agenda um horário.
	 *
	 * @throws BookingRejectedException regra de negócio ou dado inválido (veja {@link Reason})
	 * @throws BookingConflictException o horário está ocupado, inclusive quando outra requisição o ocupou ao mesmo tempo
	 */
	public Appointment book(BookingRequest request) {
		// Primeira conferência, antes de qualquer consulta: sem pet não há agendamento (ADR 0016, PR 49). Fica AQUI, e não no controlador,
		// para que qualquer caminho que passe a criar agendamento (remarcar, reserva sem conta) herde a regra.
		if (request.petId() == null) {
			throw new BookingRejectedException(Reason.PET_REQUIRED);
		}
		try {
			// A transação termina (commit ou rollback) antes de qualquer tradução de erro abaixo.
			return transaction.execute(status -> bookInTransaction(request));
		} catch (RuntimeException e) {
			if (SchedulingConflictDetector.isConflict(e)) {
				throw new BookingConflictException(e);
			}
			throw e;
		}
	}

	private Appointment bookInTransaction(BookingRequest request) {
		Provider provider = providers.findActiveById(request.providerId())
				.orElseThrow(() -> new BookingRejectedException(Reason.PROVIDER_NOT_FOUND));
		ServiceOffering service = services.findByIdAndProviderId(request.serviceId(), provider.getId())
				.filter(ServiceOffering::isActive)
				.orElseThrow(() -> new BookingRejectedException(Reason.SERVICE_NOT_FOUND_OR_INACTIVE));
		UserAccount customer = users.findById(request.customerId())
				.filter(UserAccount::isActive)
				.orElseThrow(() -> new BookingRejectedException(Reason.CUSTOMER_NOT_FOUND_OR_INACTIVE));

		Pet pet = lockPetOf(request, customer);

		Instant now = clock.instant();
		// As regras do horário (R5, R1, R2) são as MESMAS da remarcação: ficam em SlotRules (ADR 0017).
		TimeRange range = slotRules.validate(provider, request.localStart(), Duration.ofMinutes(service.durationFor(pet.getSize(), pet.getCoat())), now, null);

		Appointment draft = new Appointment(provider.getId(), service.getId(), customer.getId(), range.startsAt(), range.endsAt(), now);
		draft.attachPet(pet); // cópia dos dados do pet no momento da reserva (ADR 0016)
		draft.attachPrice(service.priceFor(pet.getSize())); // valor congelado pelo porte do pet (ADR 0019)
		Integer deposit = depositPolicy.requiredCents(provider, customer.getId());
		if (deposit != null) {
			draft.attachDeposit(deposit); // sinal por Pix pedido, valor congelado na reserva (ADR 0023)
		}
		Appointment appointment = appointments.saveAndFlush(draft);
		events.save(new AppointmentEvent(appointment.getId(), customer.getId(), AppointmentEventType.SCHEDULED, now, null));
		return appointment;
	}

	/**
	 * O pet da reserva (ADR 0016; obrigatório desde o PR 49). Bloqueia a LINHA do pet (só se for do cliente) e SÓ DEPOIS confere
	 * {@code active}: arquivar bloqueia a mesma linha, então ou a reserva vence (e o arquivamento seguinte continua permitido) ou o
	 * arquivamento vence e a reserva falha com PET_INACTIVE. Ler {@code active} sem bloquear não bastaria: entre a leitura e a gravação o
	 * tutor poderia arquivar. Pet inexistente e pet de outro cliente têm a MESMA resposta (PET_NOT_FOUND), e a conferência do dono vem antes
	 * da de ativo. Ordem global de bloqueios: cliente, pet, agendamento (aqui, o cliente só é lido).
	 */
	private Pet lockPetOf(BookingRequest request, UserAccount customer) {
		Pet pet = locks.lockPet(request.petId(), customer.getId()).orElseThrow(() -> new BookingRejectedException(Reason.PET_NOT_FOUND));
		if (!pet.isActive()) {
			throw new BookingRejectedException(Reason.PET_INACTIVE);
		}
		return pet;
	}
}
