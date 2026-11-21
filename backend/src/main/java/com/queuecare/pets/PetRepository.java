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

	/** Anonimiza TODOS os pets do tutor (ADR 0027): o que identifica sai, o pet fica arquivado. */
	@org.springframework.data.jpa.repository.Modifying
	@Query("update Pet p set p.name = 'Pet removido', p.breed = null, p.notes = null, p.healthNotes = null, p.weightKg = null, p.active = false, p.updatedAt = :now where p.customerId = :customerId")
	int anonymizeAllOf(@Param("customerId") UUID customerId, @Param("now") java.time.Instant now);

	/** Carrega o pet BLOQUEANDO a linha (reservar e arquivar/editar serializam aqui, ADR 0016 item 5). O dono vem na consulta. */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select p from Pet p where p.id = :id and p.customerId = :customerId")
	Optional<Pet> findByIdAndCustomerIdForUpdate(@Param("id") UUID id, @Param("customerId") UUID customerId);

	long countByCustomerId(UUID customerId);

	long countByCustomerIdAndActiveTrue(UUID customerId);
}
