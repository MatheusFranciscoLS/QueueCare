package com.queuecare.scheduling.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Evento do histórico do agendamento (R3). actorId nulo = evento do sistema. */
@Entity
@Table(name = "appointment_events")
public class AppointmentEvent {

	@Id
	private UUID id;

	@Column(name = "appointment_id", nullable = false)
	private UUID appointmentId;

	@Column(name = "actor_id")
	private UUID actorId;

	@Column(name = "event_type", nullable = false)
	@Enumerated(EnumType.STRING)
	private AppointmentEventType eventType;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column
	private String notes;

	/** Só nas remarcações: o horário de início antes e depois (nulos nos outros tipos). */
	@Column(name = "previous_starts_at", updatable = false)
	private Instant previousStartsAt;

	@Column(name = "new_starts_at", updatable = false)
	private Instant newStartsAt;

	protected AppointmentEvent() {
	}

	public AppointmentEvent(UUID appointmentId, UUID actorId, AppointmentEventType eventType, Instant createdAt, String notes) {
		this.id = UUID.randomUUID();
		this.appointmentId = Objects.requireNonNull(appointmentId);
		this.actorId = actorId;
		this.eventType = Objects.requireNonNull(eventType);
		this.createdAt = Objects.requireNonNull(createdAt);
		this.notes = notes;
	}

	/** Evento de remarcação: quem remarcou, quando e de qual horário para qual. */
	public static AppointmentEvent rescheduled(UUID appointmentId, UUID actorId, Instant createdAt, Instant previousStartsAt, Instant newStartsAt) {
		AppointmentEvent event = new AppointmentEvent(appointmentId, actorId, AppointmentEventType.RESCHEDULED, createdAt, null);
		event.previousStartsAt = Objects.requireNonNull(previousStartsAt);
		event.newStartsAt = Objects.requireNonNull(newStartsAt);
		return event;
	}

	public UUID getId() {
		return id;
	}

	public UUID getAppointmentId() {
		return appointmentId;
	}

	public UUID getActorId() {
		return actorId;
	}

	public AppointmentEventType getEventType() {
		return eventType;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	/** Texto livre do evento; em UNDONE (ADR 0026) é o nome da etapa desfeita. */
	public String getNotes() {
		return notes;
	}

	public Instant getPreviousStartsAt() {
		return previousStartsAt;
	}

	public Instant getNewStartsAt() {
		return newStartsAt;
	}
}
