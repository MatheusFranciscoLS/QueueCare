package com.queuecare.scheduling.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

/** Usuário (identidade e login). Nesta etapa a aplicação só lê; o cadastro vem com a autenticação (ADR 0003). */
@Entity
@Table(name = "users")
public class UserAccount {

	@Id
	private UUID id;

	@Column(nullable = false)
	@Enumerated(EnumType.STRING)
	private UserRole role;

	@Column(nullable = false)
	private boolean active;

	protected UserAccount() {
	}

	public UUID getId() {
		return id;
	}

	public UserRole getRole() {
		return role;
	}

	public boolean isActive() {
		return active;
	}
}
