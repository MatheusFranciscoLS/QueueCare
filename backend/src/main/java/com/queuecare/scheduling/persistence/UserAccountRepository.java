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

	long countByRoleAndActiveTrue(UserRole role);

	/**
	 * Listagem do ADMIN: perfis permitidos e texto no nome ou no e-mail (o chamador já passa o padrão LIKE em minúsculas, com os curingas do
	 * texto digitado escapados por '!'). Ordem estável: e-mail e depois id.
	 */
	@Query("select u from UserAccount u where u.role in :roles and (lower(u.email) like :pattern escape '!' or lower(u.name) like :pattern escape '!') "
			+ "order by lower(u.email) asc, u.id asc")
	java.util.List<UserAccount> search(@Param("roles") java.util.Collection<UserRole> roles, @Param("pattern") String pattern, org.springframework.data.domain.Pageable pageable);

	/** Carrega o usuário BLOQUEANDO a linha: trocas e redefinições de senha do mesmo usuário acontecem uma de cada vez. */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select u from UserAccount u where u.id = :id")
	Optional<UserAccount> findByIdForUpdate(@Param("id") UUID id);
}
