package com.queuecare.scheduling.application;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

/**
 * Pedido de agendamento. {@code localStart} é o horário LOCAL do prestador escolhido na interface
 * (por exemplo 10:00); o serviço converte para instante usando o fuso do prestador. {@code petId} é opcional por enquanto; quando vem,
 * tem de ser um pet ATIVO do próprio cliente.
 */
public record BookingRequest(UUID providerId, UUID serviceId, UUID customerId, LocalDateTime localStart, UUID petId) {

	/** Sem pet (compatibilidade: o pet é opcional até o PR que o torna obrigatório, ADR 0016). */
	public BookingRequest(UUID providerId, UUID serviceId, UUID customerId, LocalDateTime localStart) {
		this(providerId, serviceId, customerId, localStart, null);
	}

	public BookingRequest {
		Objects.requireNonNull(providerId, "providerId não pode ser nulo");
		Objects.requireNonNull(serviceId, "serviceId não pode ser nulo");
		Objects.requireNonNull(customerId, "customerId não pode ser nulo");
		Objects.requireNonNull(localStart, "localStart não pode ser nulo");
	}
}
