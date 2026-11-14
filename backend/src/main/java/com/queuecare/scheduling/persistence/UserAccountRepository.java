package com.queuecare.scheduling.persistence;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserAccountRepository extends JpaRepository<UserAccount, UUID> {

	/** Busca pelo e-mail JÁ normalizado (sem espaços externos, em minúsculas). */
	Optional<UserAccount> findByEmail(String normalizedEmail);

	boolean existsByRole(UserRole role);

	/** Carrega o usuário BLOQUEANDO a linha: trocas e redefinições de senha do mesmo usuário acontecem uma de cada vez. */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select u from UserAccount u where u.id = :id")
	Optional<UserAccount> findByIdForUpdate(@Param("id") UUID id);
}
