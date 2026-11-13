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
}
