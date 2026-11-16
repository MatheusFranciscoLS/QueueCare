package com.queuecare.pets;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PetRepository extends JpaRepository<Pet, UUID> {

	/** Os pets do tutor, em ordem estável (nome sem distinguir maiúsculas, depois id). */
	@Query("select p from Pet p where p.customerId = :customerId and (:includeArchived = true or p.active = true) order by lower(p.name) asc, p.id asc")
	List<Pet> findOfCustomer(@Param("customerId") UUID customerId, @Param("includeArchived") boolean includeArchived);

	/** O dono é parte da consulta: pet de outro cliente é indistinguível de pet inexistente. */
	Optional<Pet> findByIdAndCustomerId(UUID id, UUID customerId);

	/** Carrega o pet BLOQUEANDO a linha (reservar e arquivar/editar serializam aqui, ADR 0016 item 5). O dono vem na consulta. */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select p from Pet p where p.id = :id and p.customerId = :customerId")
	Optional<Pet> findByIdAndCustomerIdForUpdate(@Param("id") UUID id, @Param("customerId") UUID customerId);

	long countByCustomerId(UUID customerId);

	long countByCustomerIdAndActiveTrue(UUID customerId);
}
