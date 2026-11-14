package com.queuecare.scheduling.persistence;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserAccountRepository extends JpaRepository<UserAccount, UUID> {

	/** Busca pelo e-mail JÁ normalizado (sem espaços externos, em minúsculas). */
	Optional<UserAccount> findByEmail(String normalizedEmail);

	boolean existsByRole(UserRole role);
}
