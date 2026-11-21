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

	/** Versão das credenciais: sobe a cada troca/redefinição de senha e invalida todo token emitido antes (ADR 0006). */
	@Column(name = "token_version", nullable = false)
	private int tokenVersion;

	/** Telefone só com dígitos e DDI (ADR 0019); nulo = não informado. O formato é garantido pelo banco (users_phone_check). */
	private String phone;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	/** Versão da política de privacidade que a pessoa LEU e quando (ADR 0027); ambos nulos = não registrou. */
	@Column(name = "privacy_policy_version")
	private String privacyPolicyVersion;

	@Column(name = "privacy_accepted_at")
	private Instant privacyAcceptedAt;

	/** Quando a conta foi anonimizada a pedido da própria pessoa (ADR 0027); nulo = conta normal. */
	@Column(name = "anonymized_at")
	private Instant anonymizedAt;

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

	/** Troca a senha (já em hash), define se a troca é obrigatória no próximo acesso e invalida os tokens anteriores. */
	public void replacePassword(String newPasswordHash, boolean mustChangePassword) {
		this.passwordHash = Objects.requireNonNull(newPasswordHash);
		this.mustChangePassword = mustChangePassword;
		this.tokenVersion++;
	}

	/**
	 * Ativa ou desativa a conta e SOBE a versão das credenciais: ao reativar, nenhum token emitido antes da desativação volta a
	 * valer (o conversor só olha a versão e o estado atuais).
	 */
	public void setActive(boolean active) {
		if (this.active != active) {
			this.active = active;
			this.tokenVersion++;
		}
	}

	public int getTokenVersion() {
		return tokenVersion;
	}

	public boolean isMustChangePassword() {
		return mustChangePassword;
	}

	public String getPhone() {
		return phone;
	}

	/** Já normalizado por {@code PhoneNumber.normalize}; nulo remove o telefone. */
	public void setPhone(String phone) {
		this.phone = phone;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public String getPrivacyPolicyVersion() {
		return privacyPolicyVersion;
	}

	public Instant getPrivacyAcceptedAt() {
		return privacyAcceptedAt;
	}

	public Instant getAnonymizedAt() {
		return anonymizedAt;
	}

	/** Registra que a pessoa leu esta versão da política (a última leitura vale). */
	public void recordPrivacyAcceptance(String version, Instant at) {
		this.privacyPolicyVersion = Objects.requireNonNull(version);
		this.privacyAcceptedAt = Objects.requireNonNull(at);
	}

	/**
	 * Anonimiza a conta NO LUGAR (ADR 0027): nome, e-mail e telefone deixam de identificar a pessoa, a senha vira um hash que ninguém conhece, a conta fica inativa e
	 * todo token deixa de valer. A linha continua porque agendamentos e auditoria apontam para ela. O e-mail fica único e inválido de propósito (domínio .invalid).
	 */
	public void anonymize(String unusablePasswordHash, Instant at) {
		this.name = "Conta removida";
		this.email = "removida-" + this.id + "@removida.invalid";
		this.phone = null;
		this.passwordHash = Objects.requireNonNull(unusablePasswordHash);
		this.mustChangePassword = false;
		this.active = false;
		this.tokenVersion++;
		this.anonymizedAt = Objects.requireNonNull(at);
	}
}
