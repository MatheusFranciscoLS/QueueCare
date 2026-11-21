package com.queuecare.scheduling.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/** Pedido "avise-me se vagar um horário" (ADR 0022). Só ids e a data pedida; remover marca {@code removedAt}, nunca apaga. */
@Entity
@Table(name = "waitlist_entries")
public class WaitlistEntry {

	@Id
	private UUID id;

	@Column(name = "provider_id", nullable = false, updatable = false)
	private UUID providerId;

	@Column(name = "service_id", nullable = false, updatable = false)
	private UUID serviceId;

	@Column(name = "customer_id", nullable = false, updatable = false)
	private UUID customerId;

	@Column(name = "pet_id", nullable = false, updatable = false)
	private UUID petId;

	@Column(name = "wanted_date", nullable = false, updatable = false)
	private LocalDate wantedDate;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "called_at")
	private Instant calledAt;

	@Column(name = "removed_at")
	private Instant removedAt;

	protected WaitlistEntry() {
	}

	public WaitlistEntry(UUID providerId, UUID serviceId, UUID customerId, UUID petId, LocalDate wantedDate, Instant createdAt) {
		this.id = UUID.randomUUID();
		this.providerId = Objects.requireNonNull(providerId);
		this.serviceId = Objects.requireNonNull(serviceId);
		this.customerId = Objects.requireNonNull(customerId);
		this.petId = Objects.requireNonNull(petId);
		this.wantedDate = Objects.requireNonNull(wantedDate);
		this.createdAt = Objects.requireNonNull(createdAt);
	}

	public void markCalled(Instant at) {
		this.calledAt = Objects.requireNonNull(at);
	}

	public void remove(Instant at) {
		this.removedAt = Objects.requireNonNull(at);
	}

	public UUID getId() {
		return id;
	}

	public UUID getProviderId() {
		return providerId;
	}

	public UUID getServiceId() {
		return serviceId;
	}

	public UUID getCustomerId() {
		return customerId;
	}

	public UUID getPetId() {
		return petId;
	}

	public LocalDate getWantedDate() {
		return wantedDate;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getCalledAt() {
		return calledAt;
	}

	public Instant getRemovedAt() {
		return removedAt;
	}
}
