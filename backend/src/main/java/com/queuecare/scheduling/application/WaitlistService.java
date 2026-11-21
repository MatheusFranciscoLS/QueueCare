package com.queuecare.scheduling.application;

import com.queuecare.pets.Pet;
import com.queuecare.pets.PetService;
import com.queuecare.scheduling.application.BookingRejectedException.Reason;
import com.queuecare.scheduling.application.WaitlistExceptions.WaitlistEntryNotFoundException;
import com.queuecare.scheduling.application.WaitlistExceptions.WaitlistRejectedException;
import com.queuecare.scheduling.persistence.Appointment;
import com.queuecare.scheduling.persistence.AppointmentRepository;
import com.queuecare.scheduling.persistence.AppointmentStatus;
import com.queuecare.scheduling.persistence.Provider;
import com.queuecare.scheduling.persistence.ProviderRepository;
import com.queuecare.scheduling.persistence.ServiceOffering;
import com.queuecare.scheduling.persistence.ServiceOfferingRepository;
import com.queuecare.scheduling.persistence.UserAccount;
import com.queuecare.scheduling.persistence.UserAccountRepository;
import com.queuecare.scheduling.persistence.UserRole;
import com.queuecare.scheduling.persistence.WaitlistEntry;
import com.queuecare.scheduling.persistence.WaitlistEntryRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Lista de espera (ADR 0022). O TUTOR pede, vê e tira as próprias entradas; a EQUIPE do estúdio vê quem espera num dia, marca "chamado" e tira.
 * Sem envio e sem reserva automática. Entrada de outro tutor ou de outro estúdio responde como inexistente (404), sem pista. Quem já tem
 * agendamento ativo no estúdio naquele dia sai da lista sozinho (a lista não mostra mais, sem apagar nada).
 */
@Service
public class WaitlistService {

	/** Entradas ativas por tutor. */
	static final int MAX_ACTIVE = 10;
	/** Quantos dias à frente se pode pedir (o mesmo da consulta de horários). */
	static final int MAX_DAYS_AHEAD = SlotService.MAX_DAYS_AHEAD;

	/** Entrada vista pelo TUTOR. */
	public record MineView(UUID id, UUID providerId, String businessName, String serviceName, String petName, LocalDate wantedDate, Instant createdAt) {
	}

	/** Entrada vista pela EQUIPE: com contato e a duração prevista PARA ESSE PET (ADR 0020). */
	public record StaffView(UUID id, UUID customerId, String customerName, String customerPhone, String petName, String petSpecies, String serviceName, int durationMinutes,
			Instant createdAt, Instant calledAt) {
	}

	private final WaitlistEntryRepository entries;
	private final ProviderRepository providers;
	private final ServiceOfferingRepository services;
	private final UserAccountRepository users;
	private final AppointmentRepository appointments;
	private final PetService pets;
	private final StaffAccess staffAccess;
	private final Clock clock;

	public WaitlistService(WaitlistEntryRepository entries, ProviderRepository providers, ServiceOfferingRepository services, UserAccountRepository users,
			AppointmentRepository appointments, PetService pets, StaffAccess staffAccess, Clock clock) {
		this.entries = entries;
		this.providers = providers;
		this.services = services;
		this.users = users;
		this.appointments = appointments;
		this.pets = pets;
		this.staffAccess = staffAccess;
		this.clock = clock;
	}

	/** O tutor entra na lista. Pedir o mesmo de novo devolve a entrada que já existe (sem erro e sem duplicar). */
	@Transactional
	public WaitlistEntry join(UUID customerId, UUID providerId, UUID serviceId, UUID petId, LocalDate date) {
		UserAccount customer = users.findById(customerId).filter(UserAccount::isActive).filter(u -> u.getRole() == UserRole.CUSTOMER)
				.orElseThrow(() -> new BookingRejectedException(Reason.CUSTOMER_NOT_FOUND_OR_INACTIVE));
		Provider provider = providers.findActiveById(providerId).orElseThrow(() -> new BookingRejectedException(Reason.PROVIDER_NOT_FOUND));
		services.findByIdAndProviderId(serviceId, provider.getId()).filter(ServiceOffering::isActive)
				.orElseThrow(() -> new BookingRejectedException(Reason.SERVICE_NOT_FOUND_OR_INACTIVE));
		Pet pet = pets.findActive(customer.getId(), petId).orElseThrow(() -> new BookingRejectedException(Reason.PET_NOT_FOUND));
		LocalDate today = LocalDate.now(clock.withZone(ZoneId.of(provider.getTimeZone())));
		if (date.isBefore(today) || date.isAfter(today.plusDays(MAX_DAYS_AHEAD))) {
			throw new WaitlistRejectedException(WaitlistRejectedException.Reason.INVALID_DATE);
		}
		var existing = entries.findActiveSame(customer.getId(), provider.getId(), serviceId, pet.getId(), date);
		if (existing.isPresent()) {
			return existing.get();
		}
		if (entries.findActiveOfCustomer(customer.getId(), today.minusDays(1)).size() >= MAX_ACTIVE) {
			throw new WaitlistRejectedException(WaitlistRejectedException.Reason.WAITLIST_LIMIT);
		}
		try {
			return entries.saveAndFlush(new WaitlistEntry(provider.getId(), serviceId, customer.getId(), pet.getId(), date, clock.instant()));
		} catch (DataIntegrityViolationException e) {
			// Dois pedidos iguais ao mesmo tempo: o índice único decide, e o segundo recebe a entrada do primeiro.
			return entries.findActiveSame(customer.getId(), provider.getId(), serviceId, pet.getId(), date).orElseThrow(() -> e);
		}
	}

	/** As entradas ativas do próprio tutor, de hoje em diante, sem as de dias em que ele já tem agendamento naquele estúdio. */
	@Transactional(readOnly = true)
	public List<MineView> mine(UUID customerId) {
		LocalDate from = LocalDate.now(clock).minusDays(1);
		List<WaitlistEntry> list = entries.findActiveOfCustomer(customerId, from);
		if (list.isEmpty()) {
			return List.of();
		}
		Map<UUID, Provider> byProvider = providers.findAllById(list.stream().map(WaitlistEntry::getProviderId).distinct().toList()).stream()
				.collect(Collectors.toMap(Provider::getId, Function.identity()));
		Map<UUID, ServiceOffering> byService = services.findAllById(list.stream().map(WaitlistEntry::getServiceId).distinct().toList()).stream()
				.collect(Collectors.toMap(ServiceOffering::getId, Function.identity()));
		List<Appointment> mineAppointments = appointments.findByCustomerId(customerId, PageRequest.of(0, 200, Sort.by(Sort.Order.desc("startsAt"), Sort.Order.desc("id")))).stream().toList();
		return list.stream().filter(w -> {
			Provider p = byProvider.get(w.getProviderId());
			if (p == null) {
				return false;
			}
			ZoneId zone = ZoneId.of(p.getTimeZone());
			if (w.getWantedDate().isBefore(LocalDate.now(clock.withZone(zone)))) {
				return false; // o dia já passou
			}
			return mineAppointments.stream().noneMatch(a -> a.getProviderId().equals(w.getProviderId()) && isActive(a) && a.getStartsAt().atZone(zone).toLocalDate().equals(w.getWantedDate()));
		}).map(w -> {
			Pet pet = pets.findActive(customerId, w.getPetId()).orElse(null);
			Provider p = byProvider.get(w.getProviderId());
			ServiceOffering s = byService.get(w.getServiceId());
			return new MineView(w.getId(), w.getProviderId(), p.getBusinessName(), s == null ? null : s.getName(), pet == null ? null : pet.getName(), w.getWantedDate(), w.getCreatedAt());
		}).toList();
	}

	/** Quem espera um dia no estúdio da equipe (o estúdio vem SEMPRE do usuário autenticado), por ordem de entrada. */
	@Transactional(readOnly = true)
	public List<StaffView> day(UUID staffUserId, LocalDate date) {
		UUID providerId = staffProvider(staffUserId);
		Provider provider = providers.findById(providerId).orElseThrow(() -> new AccessDeniedException("sem vínculo com um prestador"));
		ZoneId zone = ZoneId.of(provider.getTimeZone());
		List<WaitlistEntry> list = entries.findActiveOfDay(providerId, date);
		if (list.isEmpty()) {
			return List.of();
		}
		Instant start = date.atStartOfDay(zone).toInstant();
		Instant end = date.plusDays(1).atStartOfDay(zone).toInstant();
		Set<UUID> alreadyBooked = appointments.findDayAgenda(providerId, start, end).stream().filter(WaitlistService::isActive).map(Appointment::getCustomerId)
				.collect(Collectors.toSet());
		Map<UUID, UserAccount> customers = users.findAllById(list.stream().map(WaitlistEntry::getCustomerId).distinct().toList()).stream()
				.collect(Collectors.toMap(UserAccount::getId, Function.identity()));
		Map<UUID, ServiceOffering> byService = services.findAllById(list.stream().map(WaitlistEntry::getServiceId).distinct().toList()).stream()
				.collect(Collectors.toMap(ServiceOffering::getId, Function.identity()));
		return list.stream().filter(w -> !alreadyBooked.contains(w.getCustomerId())).map(w -> {
			UserAccount customer = customers.get(w.getCustomerId());
			ServiceOffering service = byService.get(w.getServiceId());
			Pet pet = pets.findActive(w.getCustomerId(), w.getPetId()).orElse(null);
			int minutes = service == null ? 0 : pet == null ? service.getDurationMinutes() : service.durationFor(pet.getSize(), pet.getCoat());
			return new StaffView(w.getId(), w.getCustomerId(), customer == null ? null : customer.getName(), customer == null ? null : customer.getPhone(),
					pet == null ? null : pet.getName(), pet == null ? null : pet.getSpecies().name(), service == null ? null : service.getName(), minutes, w.getCreatedAt(), w.getCalledAt());
		}).toList();
	}

	/** A equipe marcou que chamou o tutor (grava quando). */
	@Transactional
	public WaitlistEntry markCalled(UUID staffUserId, UUID entryId) {
		UUID providerId = staffProvider(staffUserId);
		WaitlistEntry entry = entries.findById(entryId).filter(w -> w.getProviderId().equals(providerId) && w.getRemovedAt() == null).orElseThrow(WaitlistEntryNotFoundException::new);
		entry.markCalled(clock.instant());
		return entries.save(entry);
	}

	/** O tutor (dono) ou a equipe do estúdio tira a entrada. Tirar de novo uma entrada já tirada responde como inexistente. */
	@Transactional
	public void remove(UUID actorId, UUID entryId) {
		UserAccount actor = users.findById(actorId).filter(UserAccount::isActive).orElseThrow(WaitlistEntryNotFoundException::new);
		WaitlistEntry entry = entries.findById(entryId).filter(w -> w.getRemovedAt() == null).orElseThrow(WaitlistEntryNotFoundException::new);
		boolean owner = actor.getRole() == UserRole.CUSTOMER && entry.getCustomerId().equals(actor.getId());
		boolean staff = (actor.getRole() == UserRole.PROVIDER || actor.getRole() == UserRole.ATTENDANT)
				&& staffAccess.providerIdOf(actor).filter(entry.getProviderId()::equals).isPresent();
		if (!owner && !staff) {
			throw new WaitlistEntryNotFoundException();
		}
		entry.remove(clock.instant());
		entries.save(entry);
	}

	private UUID staffProvider(UUID staffUserId) {
		UserAccount actor = users.findById(staffUserId).filter(UserAccount::isActive).orElseThrow(() -> new AccessDeniedException("usuário inválido"));
		if (actor.getRole() != UserRole.PROVIDER && actor.getRole() != UserRole.ATTENDANT) {
			throw new AccessDeniedException("perfil sem acesso à lista de espera");
		}
		return staffAccess.providerIdOf(actor).orElseThrow(() -> new AccessDeniedException("sem vínculo com um prestador"));
	}

	/** Agendamento que "ocupa" o dia do tutor: tudo que não foi cancelado nem faltou. */
	private static boolean isActive(Appointment a) {
		return a.getStatus() != AppointmentStatus.CANCELLED && a.getStatus() != AppointmentStatus.NO_SHOW;
	}
}
