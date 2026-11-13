package com.queuecare.scheduling.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ServiceOfferingRepository extends JpaRepository<ServiceOffering, UUID> {

	/** Só devolve o serviço se ele pertencer ao prestador informado. */
	Optional<ServiceOffering> findByIdAndProviderId(UUID id, UUID providerId);

	List<ServiceOffering> findByProviderIdOrderByName(UUID providerId);
}
