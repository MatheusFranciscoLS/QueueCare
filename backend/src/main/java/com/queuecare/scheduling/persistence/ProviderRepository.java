package com.queuecare.scheduling.persistence;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProviderRepository extends JpaRepository<Provider, UUID> {

	Optional<Provider> findByUserId(UUID userId);

	/**
	 * Igual a {@link #findByUserId}, mas BLOQUEIA a linha do negócio até o fim da transação ({@code SELECT ... FOR UPDATE}).
	 * Serve para serializar edições concorrentes do mesmo prestador (substituir janelas, alterar o negócio): a segunda
	 * espera a primeira confirmar e depois aplica a sua, em vez de as duas se misturarem.
	 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select p from Provider p where p.userId = :userId")
	Optional<Provider> findByUserIdForUpdate(@Param("userId") UUID userId);
}
