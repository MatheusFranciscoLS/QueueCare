package com.queuecare.auth;

import com.queuecare.scheduling.persistence.UserAccount;
import com.queuecare.scheduling.persistence.UserAccountRepository;
import jakarta.persistence.EntityManager;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Bloqueios de linha das operações sobre contas (senha, ativação, equipe), todos com espera LIMITADA: o limite
 * ({@code queuecare.security.user-lock-timeout}, padrão 3 s, aceito de 1 ms a 60 s) vale só dentro da transação em curso
 * ({@code set_config(..., true)}); estourou, o PostgreSQL aborta e a API responde 409 RESOURCE_BUSY, nunca 500 nem espera infinita.
 * Ordem de bloqueios das operações (sempre esta, nunca invertida): 1) linha de controle de administração (só quando a operação
 * muda o estado de contas de ADMIN ou depende dele), 2) linha do usuário alvo. Sem ordens divergentes não há deadlock por ordem.
 * O bloqueio do usuário é {@code FOR NO KEY UPDATE} (é o que o Hibernate gera para PESSIMISTIC_WRITE no PostgreSQL): NÃO conflita
 * com o {@code FOR KEY SHARE} que a gravação da auditoria (chaves estrangeiras para o ator e o alvo) toma nas linhas de usuário,
 * então "A desativa B" e "B redefine a senha de A" não se travam em ciclo. Isso é fixado por teste (a auditoria é artificialmente
 * lenta para alargar a janela da corrida), para que uma troca de versão do Hibernate ou do dialeto não o quebre em silêncio.
 * Chamar SEMPRE dentro de uma transação.
 */
@Component
public class RowLocks {

	private final EntityManager entityManager;
	private final UserAccountRepository users;
	private final long lockTimeoutMillis;

	public RowLocks(EntityManager entityManager, UserAccountRepository users, @Value("${queuecare.security.user-lock-timeout:3s}") Duration lockTimeout) {
		this.entityManager = entityManager;
		this.users = users;
		if (lockTimeout.compareTo(Duration.ofMillis(1)) < 0 || lockTimeout.compareTo(Duration.ofSeconds(60)) > 0) {
			throw new IllegalArgumentException("queuecare.security.user-lock-timeout deve estar entre 1 ms e 60 s");
		}
		this.lockTimeoutMillis = lockTimeout.toMillis();
	}

	private void limitWait() {
		entityManager.createNativeQuery("select set_config('lock_timeout', ?1, true)").setParameter(1, lockTimeoutMillis + "ms").getSingleResult();
	}

	/** Carrega o usuário bloqueando a linha (vazio se não existe). */
	public Optional<UserAccount> lockUser(UUID userId) {
		limitWait();
		return users.findByIdForUpdate(userId);
	}

	/** Trava a linha única de controle: quem muda o estado de contas serializa aqui ANTES de decidir. */
	public void lockAdminControl() {
		limitWait();
		entityManager.createNativeQuery("select id from admin_control where id = 1 for update").getSingleResult();
	}
}
