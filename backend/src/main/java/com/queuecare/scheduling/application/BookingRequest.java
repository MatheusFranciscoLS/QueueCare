package com.queuecare.scheduling.application;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

/**
 * Pedido de agendamento. {@code localStart} é o horário LOCAL do prestador escolhido na interface
 * (por exemplo 10:00); o serviço converte para instante usando o fuso do prestador.
 */
public record BookingRequest(UUID providerId, UUID serviceId, UUID customerId, LocalDateTime localStart) {

	public BookingRequest {
		Objects.requireNonNull(providerId, "providerId não pode ser nulo");
		Objects.requireNonNull(serviceId, "serviceId não pode ser nulo");
		Objects.requireNonNull(customerId, "customerId não pode ser nulo");
		Objects.requireNonNull(localStart, "localStart não pode ser nulo");
	}
}
