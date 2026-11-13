package com.queuecare.scheduling.persistence;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AvailabilityWindowRepository extends JpaRepository<AvailabilityWindowEntity, UUID> {

	List<AvailabilityWindowEntity> findByProviderId(UUID providerId);
}
