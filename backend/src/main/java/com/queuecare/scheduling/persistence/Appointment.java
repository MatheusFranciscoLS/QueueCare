package com.queuecare.scheduling.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import com.queuecare.pets.Pet;
import com.queuecare.pets.PetSize;
import com.queuecare.pets.PetSpecies;
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

	/**
	 * Pet e CÓPIA (snapshot) dos dados dele no momento da reserva (ADR 0016). Só são gravados na criação (updatable = false): o JPA nunca
	 * reescreve a cópia ao mudar o estado do agendamento. Quem a atualiza, enquanto SCHEDULED, é a consulta em massa
	 * {@link AppointmentRepository#refreshPetSnapshot}, que também sobe o version.
	 */
	@Column(name = "pet_id", updatable = false)
	private UUID petId;

	@Column(name = "pet_name", updatable = false)
	private String petName;

	@Column(name = "pet_species", updatable = false)
	@Enumerated(EnumType.STRING)
	private PetSpecies petSpecies;

	@Column(name = "pet_breed", updatable = false)
	private String petBreed;

	@Column(name = "pet_size", updatable = false)
	@Enumerated(EnumType.STRING)
	private PetSize petSize;

	@Column(name = "pet_notes", updatable = false)
	private String petNotes;

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

	/** Liga o pet ao agendamento NOVO e copia os dados dele (snapshot). Só na criação, antes de gravar. */
	public void attachPet(Pet pet) {
		Objects.requireNonNull(pet);
		this.petId = pet.getId();
		this.petName = pet.getName();
		this.petSpecies = pet.getSpecies();
		this.petBreed = pet.getBreed();
		this.petSize = pet.getSize();
		this.petNotes = pet.getNotes();
	}

	/** Move o agendamento para outro horário (ADR 0017). O serviço confere regras e situação antes; aqui só se muda. Mesma duração, mesmo pet. */
	public void reschedule(Instant newStartsAt, Instant newEndsAt) {
		this.startsAt = Objects.requireNonNull(newStartsAt);
		this.endsAt = Objects.requireNonNull(newEndsAt);
	}

	/** Cancela o agendamento (libera o horário no banco: a restrição de exclusão ignora CANCELLED). */
	public void cancel() {
		this.status = AppointmentStatus.CANCELLED;
	}

	/** A pessoa chegou (SCHEDULED para CHECKED_IN). O serviço confere a situação antes; aqui só se muda. */
	public void checkIn() {
		this.status = AppointmentStatus.CHECKED_IN;
	}

	/** Atendimento concluído (CHECKED_IN para COMPLETED). */
	public void complete() {
		this.status = AppointmentStatus.COMPLETED;
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

	/** Nulo nos agendamentos sem pet (antigos ou feitos sem escolher um). */
	public UUID getPetId() {
		return petId;
	}

	public String getPetName() {
		return petName;
	}

	public PetSpecies getPetSpecies() {
		return petSpecies;
	}

	public String getPetBreed() {
		return petBreed;
	}

	public PetSize getPetSize() {
		return petSize;
	}

	public String getPetNotes() {
		return petNotes;
	}
}
