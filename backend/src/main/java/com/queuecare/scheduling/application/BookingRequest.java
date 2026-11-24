package com.queuecare.scheduling.application;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

/**
 * Pedido de agendamento. {@code localStart} é o horário LOCAL do prestador escolhido na interface
 * (por exemplo 10:00); o serviço converte para instante usando o fuso do prestador. {@code petId} é OBRIGATÓRIO (ADR 0016, PR 49): tem de
 * ser um pet ATIVO do próprio cliente. A regra é do serviço de agendamento (nulo vira PET_REQUIRED), não do controlador, para que todo
 * caminho que crie agendamento herde a exigência.
 *
 * <p>{@code actorId}: quem faz a reserva, para o histórico. Nulo = o próprio cliente; a equipe que reserva pelo balcão (ADR 0033) informa o seu id,
 * e o evento aparece como feito pela equipe.
 */
public record BookingRequest(UUID providerId, UUID serviceId, UUID customerId, LocalDateTime localStart, UUID petId, UUID actorId) {

	public BookingRequest {
		Objects.requireNonNull(providerId, "providerId não pode ser nulo");
		Objects.requireNonNull(serviceId, "serviceId não pode ser nulo");
		Objects.requireNonNull(customerId, "customerId não pode ser nulo");
		Objects.requireNonNull(localStart, "localStart não pode ser nulo");
	}

	/** Reserva feita pelo próprio cliente. */
	public BookingRequest(UUID providerId, UUID serviceId, UUID customerId, LocalDateTime localStart, UUID petId) {
		this(providerId, serviceId, customerId, localStart, petId, null);
	}
}
