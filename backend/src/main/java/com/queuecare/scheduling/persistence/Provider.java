package com.queuecare.scheduling.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.Objects;
import java.util.UUID;

/** Dados do negócio do prestador: fuso IANA, antecedência mínima e prazo de cancelamento. */
@Entity
@Table(name = "providers")
public class Provider {

	@Id
	private UUID id;

	@Column(name = "user_id", nullable = false)
	private UUID userId;

	@Column(name = "business_name", nullable = false)
	private String businessName;

	@Column(name = "time_zone", nullable = false)
	private String timeZone;

	@Column(name = "minimum_notice_minutes", nullable = false)
	private int minimumNoticeMinutes;

	@Column(name = "cancellation_deadline_minutes", nullable = false)
	private int cancellationDeadlineMinutes;

	protected Provider() {
	}

	public Provider(UUID userId, String businessName, String timeZone, int minimumNoticeMinutes, int cancellationDeadlineMinutes) {
		this.id = UUID.randomUUID();
		this.userId = Objects.requireNonNull(userId);
		update(businessName, timeZone, minimumNoticeMinutes, cancellationDeadlineMinutes);
	}

	/** Atualiza as configurações do negócio (a validação de valores é feita pelo serviço). */
	public void update(String businessName, String timeZone, int minimumNoticeMinutes, int cancellationDeadlineMinutes) {
		this.businessName = Objects.requireNonNull(businessName);
		this.timeZone = Objects.requireNonNull(timeZone);
		this.minimumNoticeMinutes = minimumNoticeMinutes;
		this.cancellationDeadlineMinutes = cancellationDeadlineMinutes;
	}

	public UUID getId() {
		return id;
	}

	/** Usuário (login) dono deste negócio. */
	public UUID getUserId() {
		return userId;
	}

	public String getBusinessName() {
		return businessName;
	}

	public String getTimeZone() {
		return timeZone;
	}

	public int getMinimumNoticeMinutes() {
		return minimumNoticeMinutes;
	}

	public int getCancellationDeadlineMinutes() {
		return cancellationDeadlineMinutes;
	}
}
