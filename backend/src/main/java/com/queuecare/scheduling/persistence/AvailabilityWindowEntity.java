package com.queuecare.scheduling.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.Objects;
import java.util.UUID;

/** Janela semanal de atendimento em horário local (tabela availability_windows). day_of_week segue ISO: 1 = segunda. */
@Entity
@Table(name = "availability_windows")
public class AvailabilityWindowEntity {

	@Id
	private UUID id;

	@Column(name = "provider_id", nullable = false)
	private UUID providerId;

	@Column(name = "day_of_week", nullable = false)
	private short dayOfWeek;

	@Column(name = "start_time", nullable = false)
	private LocalTime startTime;

	@Column(name = "end_time", nullable = false)
	private LocalTime endTime;

	protected AvailabilityWindowEntity() {
	}

	public AvailabilityWindowEntity(UUID providerId, DayOfWeek dayOfWeek, LocalTime startTime, LocalTime endTime) {
		this.id = UUID.randomUUID();
		this.providerId = Objects.requireNonNull(providerId);
		this.dayOfWeek = (short) dayOfWeek.getValue();
		this.startTime = Objects.requireNonNull(startTime);
		this.endTime = Objects.requireNonNull(endTime);
	}

	public UUID getProviderId() {
		return providerId;
	}

	public DayOfWeek dayOfWeek() {
		return DayOfWeek.of(dayOfWeek);
	}

	public LocalTime getStartTime() {
		return startTime;
	}

	public LocalTime getEndTime() {
		return endTime;
	}
}
