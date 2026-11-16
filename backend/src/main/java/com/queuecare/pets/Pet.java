package com.queuecare.pets;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Pet (animal atendido), cadastrado e mantido pelo tutor (CUSTOMER). Não há exclusão: arquivar é {@code active = false}.
 * Os textos chegam JÁ normalizados ({@link PetText}); o banco recusa qualquer valor fora dessa forma (ADR 0016, item 1).
 */
@Entity
@Table(name = "pets")
public class Pet {

	@Id
	private UUID id;

	@Column(name = "customer_id", nullable = false, updatable = false)
	private UUID customerId;

	@Column(nullable = false)
	private String name;

	@Column(nullable = false)
	@Enumerated(EnumType.STRING)
	private PetSpecies species;

	private String breed;

	@Enumerated(EnumType.STRING)
	private PetSize size;

	private String notes;

	@Column(nullable = false)
	private boolean active;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected Pet() {
	}

	public Pet(UUID customerId, PetText.Normalized data, Instant now) {
		this.id = UUID.randomUUID();
		this.customerId = Objects.requireNonNull(customerId);
		this.active = true;
		this.createdAt = Objects.requireNonNull(now);
		apply(data, now);
	}

	public void update(PetText.Normalized data, Instant now) {
		apply(data, now);
	}

	public void archive(Instant now) {
		this.active = false;
		this.updatedAt = now;
	}

	public void reactivate(Instant now) {
		this.active = true;
		this.updatedAt = now;
	}

	private void apply(PetText.Normalized data, Instant now) {
		this.name = Objects.requireNonNull(data.name());
		this.species = Objects.requireNonNull(data.species());
		this.breed = data.breed();
		this.size = data.size();
		this.notes = data.notes();
		this.updatedAt = Objects.requireNonNull(now);
	}

	public UUID getId() {
		return id;
	}

	public UUID getCustomerId() {
		return customerId;
	}

	public String getName() {
		return name;
	}

	public PetSpecies getSpecies() {
		return species;
	}

	public String getBreed() {
		return breed;
	}

	public PetSize getSize() {
		return size;
	}

	public String getNotes() {
		return notes;
	}

	public boolean isActive() {
		return active;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}
}
