package com.queuecare.audit;

import com.queuecare.scheduling.persistence.UserRole;
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
 * Registro de auditoria: quem (ator) fez o quê (ação) sobre quem (alvo e perfil dele), e quando. Somente de acréscimo (o
 * banco recusa UPDATE e DELETE) e SEM segredos: nunca senha, hash, token nem e-mail (o alvo é identificado pelo id).
 */
@Entity
@Table(name = "audit_log")
public class AuditLog {

	@Id
	private UUID id;

	@Column(name = "occurred_at", nullable = false, updatable = false)
	private Instant occurredAt;

	@Column(name = "actor_user_id", nullable = false, updatable = false)
	private UUID actorUserId;

	@Column(nullable = false, updatable = false)
	@Enumerated(EnumType.STRING)
	private AuditAction action;

	@Column(name = "target_user_id", updatable = false)
	private UUID targetUserId;

	@Column(name = "target_role", updatable = false)
	@Enumerated(EnumType.STRING)
	private UserRole targetRole;

	protected AuditLog() {
	}

	AuditLog(Instant occurredAt, UUID actorUserId, AuditAction action, UUID targetUserId, UserRole targetRole) {
		this.id = UUID.randomUUID();
		this.occurredAt = Objects.requireNonNull(occurredAt);
		this.actorUserId = Objects.requireNonNull(actorUserId);
		this.action = Objects.requireNonNull(action);
		this.targetUserId = targetUserId;
		this.targetRole = targetRole;
	}

	public UUID getId() {
		return id;
	}

	public Instant getOccurredAt() {
		return occurredAt;
	}

	public UUID getActorUserId() {
		return actorUserId;
	}

	public AuditAction getAction() {
		return action;
	}

	public UUID getTargetUserId() {
		return targetUserId;
	}

	public UserRole getTargetRole() {
		return targetRole;
	}
}
