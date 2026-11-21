package com.queuecare.scheduling.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/** Folga, feriado ou férias (ADR 0024): o estúdio fecha por dias inteiros, de {@code startsOn} a {@code endsOn}, inclusive, na data local do estúdio. */
@Entity
@Table(name = "time_off")
public class TimeOff {

	@Id
	private UUID id;

	@Column(name = "provider_id", nullable = false, updatable = false)
	private UUID providerId;

	@Column(name = "starts_on", nullable = false, updatable = false)
	private LocalDate startsOn;

	@Column(name = "ends_on", nullable = false, updatable = false)
	private LocalDate endsOn;

	@Column(name = "reason", updatable = false)
	private String reason;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	protected TimeOff() {
	}

	public TimeOff(UUID providerId, LocalDate startsOn, LocalDate endsOn, String reason, Instant createdAt) {
		this.id = UUID.randomUUID();
		this.providerId = Objects.requireNonNull(providerId);
		this.startsOn = Objects.requireNonNull(startsOn);
		this.endsOn = Objects.requireNonNull(endsOn);
		this.reason = reason;
		this.createdAt = Objects.requireNonNull(createdAt);
	}

	public boolean covers(LocalDate date) {
		return !date.isBefore(startsOn) && !date.isAfter(endsOn);
	}

	public UUID getId() {
		return id;
	}

	public UUID getProviderId() {
		return providerId;
	}

	public LocalDate getStartsOn() {
		return startsOn;
	}

	public LocalDate getEndsOn() {
		return endsOn;
	}

	public String getReason() {
		return reason;
	}
}
