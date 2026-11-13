package com.queuecare.scheduling.persistence;

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AppointmentRepository extends JpaRepository<Appointment, UUID> {

	java.util.List<Appointment> findByCustomerId(UUID customerId, org.springframework.data.domain.Pageable pageable);

	/** Agendamentos ATIVOS (não cancelados) do prestador que se sobrepõem a [start, end): base da consulta de horários livres. */
	@Query("select a from Appointment a where a.providerId = :providerId and a.status <> "
			+ "com.queuecare.scheduling.persistence.AppointmentStatus.CANCELLED and a.startsAt < :end and a.endsAt > :start")
	java.util.List<Appointment> findActiveOverlapping(@Param("providerId") UUID providerId, @Param("start") Instant start, @Param("end") Instant end);

	/**
	 * Há agendamento ativo (não cancelado) do prestador que se sobreponha a {@code [start, end)}?
	 * Mesma condição de TimeRange.overlaps: {@code a < d} e {@code c < b}. Esta checagem só serve para dar uma
	 * mensagem clara; quem GARANTE a exclusividade, mesmo sob concorrência, é o banco (ADR 0002).
	 */
	@Query("select count(a) > 0 from Appointment a where a.providerId = :providerId and a.status <> "
			+ "com.queuecare.scheduling.persistence.AppointmentStatus.CANCELLED and a.startsAt < :end and a.endsAt > :start")
	boolean existsActiveOverlapping(@Param("providerId") UUID providerId, @Param("start") Instant start, @Param("end") Instant end);
}
