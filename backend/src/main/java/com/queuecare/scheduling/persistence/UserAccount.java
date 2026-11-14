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

/**
 * Usuário (identidade e login). O e-mail é guardado normalizado (sem espaços externos, em minúsculas) e a senha
 * somente como hash BCrypt. O hash nunca deve sair em respostas nem em logs.
 */
@Entity
@Table(name = "users")
public class UserAccount {

	@Id
	private UUID id;

	@Column(nullable = false)
	private String name;

	@Column(nullable = false)
	private String email;

	@Column(name = "password_hash", nullable = false)
	private String passwordHash;

	@Column(nullable = false)
	@Enumerated(EnumType.STRING)
	private UserRole role;

	@Column(nullable = false)
	private boolean active;

	@Column(name = "must_change_password", nullable = false)
	private boolean mustChangePassword;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	protected UserAccount() {
	}

	public UserAccount(String name, String email, String passwordHash, UserRole role, Instant createdAt) {
		this(name, email, passwordHash, role, createdAt, false);
	}

	/** {@code mustChangePassword}: a senha inicial foi definida por outra pessoa; até trocá-la o acesso é restrito. */
	public UserAccount(String name, String email, String passwordHash, UserRole role, Instant createdAt, boolean mustChangePassword) {
		this.mustChangePassword = mustChangePassword;
		this.id = UUID.randomUUID();
		this.name = Objects.requireNonNull(name);
		this.email = Objects.requireNonNull(email);
		this.passwordHash = Objects.requireNonNull(passwordHash);
		this.role = Objects.requireNonNull(role);
		this.createdAt = Objects.requireNonNull(createdAt);
		this.active = true;
	}

	public UUID getId() {
		return id;
	}

	public String getName() {
		return name;
	}

	public String getEmail() {
		return email;
	}

	public String getPasswordHash() {
		return passwordHash;
	}

	public UserRole getRole() {
		return role;
	}

	public boolean isActive() {
		return active;
	}

	public boolean isMustChangePassword() {
		return mustChangePassword;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}
}
