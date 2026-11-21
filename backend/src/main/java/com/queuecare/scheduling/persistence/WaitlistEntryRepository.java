package com.queuecare.scheduling.persistence;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WaitlistEntryRepository extends JpaRepository<WaitlistEntry, UUID> {

	/** A entrada ATIVA igual (mesmo tutor, estúdio, serviço, pet e dia), se houver: pedir de novo devolve a mesma. */
	@Query("select w from WaitlistEntry w where w.customerId = :customerId and w.providerId = :providerId and w.serviceId = :serviceId and w.petId = :petId "
			+ "and w.wantedDate = :date and w.removedAt is null")
	Optional<WaitlistEntry> findActiveSame(@Param("customerId") UUID customerId, @Param("providerId") UUID providerId, @Param("serviceId") UUID serviceId,
			@Param("petId") UUID petId, @Param("date") LocalDate date);

	/** Entradas ativas do tutor de hoje em diante (a data é conferida pelo serviço no fuso de cada estúdio), por dia e ordem de entrada. */
	@Query("select w from WaitlistEntry w where w.customerId = :customerId and w.removedAt is null and w.wantedDate >= :from order by w.wantedDate asc, w.createdAt asc, w.id asc")
	List<WaitlistEntry> findActiveOfCustomer(@Param("customerId") UUID customerId, @Param("from") LocalDate from);

	/** Quem espera um dia num estúdio, por ordem de entrada. */
	@Query("select w from WaitlistEntry w where w.providerId = :providerId and w.wantedDate = :date and w.removedAt is null order by w.createdAt asc, w.id asc")
	List<WaitlistEntry> findActiveOfDay(@Param("providerId") UUID providerId, @Param("date") LocalDate date);
}
