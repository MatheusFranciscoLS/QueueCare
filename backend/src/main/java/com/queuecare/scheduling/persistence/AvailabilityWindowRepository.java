package com.queuecare.scheduling.persistence;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AvailabilityWindowRepository extends JpaRepository<AvailabilityWindowEntity, UUID> {

	List<AvailabilityWindowEntity> findByProviderId(UUID providerId);

	@Modifying
	@Query("delete from AvailabilityWindowEntity w where w.providerId = :providerId")
	void deleteAllByProviderId(@Param("providerId") UUID providerId);
}
