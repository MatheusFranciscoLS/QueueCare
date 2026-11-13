package com.queuecare.scheduling.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProviderStaffRepository extends JpaRepository<ProviderStaff, UUID> {

	Optional<ProviderStaff> findByUserId(UUID userId);

	List<ProviderStaff> findByProviderId(UUID providerId);

	/** Desvincula; devolve quantas linhas foram apagadas (0 = esse usuário não é atendente DESTE prestador). */
	@Modifying
	@Query("delete from ProviderStaff s where s.providerId = :providerId and s.userId = :userId")
	int deleteByProviderIdAndUserId(@Param("providerId") UUID providerId, @Param("userId") UUID userId);
}
