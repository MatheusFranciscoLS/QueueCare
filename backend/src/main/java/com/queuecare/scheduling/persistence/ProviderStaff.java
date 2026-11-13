package com.queuecare.scheduling.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Vínculo de um ATENDENTE a um prestador (tabela provider_staff). Um atendente pertence a no máximo um prestador.
 * O perfil é gravado SEMPRE como ATTENDANT (nunca vem de requisição): o banco confere com a FK composta (user_id, role).
 * O vínculo usa o id do usuário, nunca o e-mail.
 */
@Entity
@Table(name = "provider_staff")
public class ProviderStaff {

	static final String ATTENDANT_ROLE = "ATTENDANT";

	@Id
	private UUID id;

	@Column(name = "provider_id", nullable = false, updatable = false)
	private UUID providerId;

	@Column(name = "user_id", nullable = false, updatable = false)
	private UUID userId;

	@Column(nullable = false, updatable = false)
	private String role;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	protected ProviderStaff() {
	}

	public ProviderStaff(UUID providerId, UUID userId, Instant createdAt) {
		this.id = UUID.randomUUID();
		this.providerId = Objects.requireNonNull(providerId);
		this.userId = Objects.requireNonNull(userId);
		this.role = ATTENDANT_ROLE;
		this.createdAt = Objects.requireNonNull(createdAt);
	}

	public UUID getId() {
		return id;
	}

	public UUID getProviderId() {
		return providerId;
	}

	public UUID getUserId() {
		return userId;
	}
}
