package com.queuecare.scheduling.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

/** Serviço oferecido por um prestador (tabela services), com duração em minutos. */
@Entity
@Table(name = "services")
public class ServiceOffering {

	@Id
	private UUID id;

	@Column(name = "provider_id", nullable = false)
	private UUID providerId;

	@Column(name = "duration_minutes", nullable = false)
	private int durationMinutes;

	@Column(nullable = false)
	private boolean active;

	protected ServiceOffering() {
	}

	public UUID getId() {
		return id;
	}

	public UUID getProviderId() {
		return providerId;
	}

	public int getDurationMinutes() {
		return durationMinutes;
	}

	public boolean isActive() {
		return active;
	}
}
