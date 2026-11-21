package com.queuecare.scheduling.persistence;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TimeOffRepository extends JpaRepository<TimeOff, UUID> {

	/** O que ainda vale de {@code from} em diante (período em andamento ou futuro), pelo início e pelo id (ordem estável). */
	@Query("select t from TimeOff t where t.providerId = :providerId and t.endsOn >= :from order by t.startsOn asc, t.id asc")
	List<TimeOff> findUpcoming(@Param("providerId") UUID providerId, @Param("from") LocalDate from);

	/** O período que cobre o dia, se houver (o mais antigo, em caso de sobreposição legada). */
	@Query("select t from TimeOff t where t.providerId = :providerId and t.startsOn <= :day and t.endsOn >= :day order by t.startsOn asc, t.id asc")
	List<TimeOff> findCovering(@Param("providerId") UUID providerId, @Param("day") LocalDate day);

	/** Algum período se sobrepõe a [start, end]? (para recusar duplicatas ao criar) */
	@Query("select count(t) > 0 from TimeOff t where t.providerId = :providerId and t.startsOn <= :end and t.endsOn >= :start")
	boolean existsOverlapping(@Param("providerId") UUID providerId, @Param("start") LocalDate start, @Param("end") LocalDate end);

	long countByProviderId(UUID providerId);

	Optional<TimeOff> findByIdAndProviderId(UUID id, UUID providerId);
}
