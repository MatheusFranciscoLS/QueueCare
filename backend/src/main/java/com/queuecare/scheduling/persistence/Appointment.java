package com.queuecare.scheduling.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Agendamento (entidade central). Instantes em UTC. A exclusividade do horário é garantida pelo banco
 * (restrição appointments_no_overlap, ADR 0002); {@code version} só protege atualizações concorrentes do mesmo registro.
 */
@Entity
@Table(name = "appointments")
public class Appointment {

	@Id
	private UUID id;

	@Column(name = "provider_id", nullable = false)
	private UUID providerId;

	@Column(name = "service_id", nullable = false)
	private UUID serviceId;

	@Column(name = "customer_id", nullable = false)
	private UUID customerId;

	@Column(name = "starts_at", nullable = false)
	private Instant startsAt;

	@Column(name = "ends_at", nullable = false)
	private Instant endsAt;

	@Column(nullable = false)
	@Enumerated(EnumType.STRING)
	private AppointmentStatus status;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Version
	@Column(nullable = false)
	private long version;

	protected Appointment() {
	}

	public Appointment(UUID providerId, UUID serviceId, UUID customerId, Instant startsAt, Instant endsAt, Instant createdAt) {
		this.id = UUID.randomUUID();
		this.providerId = Objects.requireNonNull(providerId);
		this.serviceId = Objects.requireNonNull(serviceId);
		this.customerId = Objects.requireNonNull(customerId);
		this.startsAt = Objects.requireNonNull(startsAt);
		this.endsAt = Objects.requireNonNull(endsAt);
		this.createdAt = Objects.requireNonNull(createdAt);
		this.status = AppointmentStatus.SCHEDULED;
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

	public Instant getStartsAt() {
		return startsAt;
	}

	public Instant getEndsAt() {
		return endsAt;
	}

	public AppointmentStatus getStatus() {
		return status;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}
}
