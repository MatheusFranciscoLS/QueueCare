package com.queuecare.scheduling.application;

import com.queuecare.auth.CounterCustomers;
import com.queuecare.auth.CounterCustomers.Owned;
import com.queuecare.scheduling.application.CatalogService.ServiceView;
import com.queuecare.scheduling.application.SlotService.Slot;
import com.queuecare.scheduling.persistence.Appointment;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Reserva pelo balcão (ADR 0033): a equipe vê horários e agenda por um tutor que o PRÓPRIO estúdio cadastrou. Toda a regra de agenda (horário local, antecedência,
 * expediente, folgas, sobreposição, duração pelo pet, sinal) é a de {@link BookingService} e {@link SlotService}, sem cópia: aqui só se confere QUEM pode agir
 * por quem ({@link CounterCustomers#requireOwned}, 404 igual para qualquer alvo inválido) e se registra a equipe como autora da reserva.
 */
@Service
public class CounterBooking {

	private final CounterCustomers counter;
	private final SlotService slots;
	private final BookingService booking;
	private final StaffAccess staffAccess;
	private final CatalogService catalog;

	public CounterBooking(CounterCustomers counter, SlotService slots, BookingService booking, StaffAccess staffAccess, CatalogService catalog) {
		this.staffAccess = staffAccess;
		this.catalog = catalog;
		this.counter = counter;
		this.slots = slots;
		this.booking = booking;
	}

	/** Os serviços ativos do estúdio da equipe (o catálogo é do tutor; o estúdio vem do vínculo da equipe, nunca de parâmetro). */
	public List<ServiceView> services(UUID staffUserId) {
		UUID providerId = staffAccess.providerIdOf(staffUserId).orElseThrow(() -> new org.springframework.security.access.AccessDeniedException("sem vínculo com um prestador"));
		return catalog.services(providerId);
	}

	/** Horários livres do serviço no dia, com a duração do pet do tutor (o pet precisa ser do tutor e estar ativo; senão vale a duração base). */
	public List<Slot> freeSlots(UUID staffUserId, UUID customerId, UUID serviceId, LocalDate date, UUID petId) {
		Owned owned = counter.requireOwned(staffUserId, customerId);
		return slots.freeSlots(owned.providerId(), serviceId, date, null, owned.customer().getId(), petId);
	}

	/** Agenda no estúdio da equipe, em nome do tutor; o histórico registra a equipe como quem fez. */
	public Appointment book(UUID staffUserId, UUID customerId, UUID serviceId, LocalDateTime localStart, UUID petId) {
		Owned owned = counter.requireOwned(staffUserId, customerId);
		return booking.book(new BookingRequest(owned.providerId(), serviceId, owned.customer().getId(), localStart, petId, staffUserId));
	}
}
