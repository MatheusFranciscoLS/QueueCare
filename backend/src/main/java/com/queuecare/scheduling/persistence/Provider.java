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

	/** Endereço público (único, minúsculas); nulo enquanto o prestador não escolheu. */
	@Column(name = "slug")
	private String slug;

	/** Telefone do estúdio (ADR 0021): só dígitos com DDI, ou nulo. O banco repete o formato (providers_phone_check). */
	@Column(name = "phone")
	private String phone;

	/** A página pública só aparece quando publicada (e com slug). */
	@Column(name = "published", nullable = false)
	private boolean published;

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

	/** Define o endereço público e se a página está publicada (a validação é do serviço; o banco confere o formato e a unicidade). */
	public void setPublicPage(String slug, boolean published) {
		this.slug = slug;
		this.published = published;
	}

	public String getPhone() {
		return phone;
	}

	/** Define o telefone já normalizado (PhoneNumber.normalize); nulo limpa. */
	public void setPhone(String phone) {
		this.phone = phone;
	}

	public String getSlug() {
		return slug;
	}

	public boolean isPublished() {
		return published;
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
