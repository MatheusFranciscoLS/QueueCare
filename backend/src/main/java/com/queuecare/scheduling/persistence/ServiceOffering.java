package com.queuecare.scheduling.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.Objects;
import java.util.UUID;

/** Serviço oferecido por um prestador (tabela services), com duração em minutos. */
@Entity
@Table(name = "services")
public class ServiceOffering {

	@Id
	private UUID id;

	@Column(name = "provider_id", nullable = false)
	private UUID providerId;

	@Column(nullable = false)
	private String name;

	@Column(name = "duration_minutes", nullable = false)
	private int durationMinutes;

	@Column(nullable = false)
	private boolean active;

	protected ServiceOffering() {
	}

	public ServiceOffering(UUID providerId, String name, int durationMinutes) {
		this.id = UUID.randomUUID();
		this.providerId = Objects.requireNonNull(providerId);
		this.name = Objects.requireNonNull(name);
		this.durationMinutes = durationMinutes;
		this.active = true;
	}

	/** Edita o serviço. Desativar não apaga: agendamentos antigos continuam apontando para ele. */
	public void update(String name, int durationMinutes, boolean active) {
		this.name = Objects.requireNonNull(name);
		this.durationMinutes = durationMinutes;
		this.active = active;
	}

	public UUID getId() {
		return id;
	}

	public UUID getProviderId() {
		return providerId;
	}

	public String getName() {
		return name;
	}

	public int getDurationMinutes() {
		return durationMinutes;
	}

	public boolean isActive() {
		return active;
	}
}
