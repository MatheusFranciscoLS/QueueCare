package com.queuecare.scheduling.persistence;

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AppointmentRepository extends JpaRepository<Appointment, UUID> {

	java.util.List<Appointment> findByCustomerId(UUID customerId, org.springframework.data.domain.Pageable pageable);

	/** Agenda do dia: agendamentos NÃO cancelados do prestador que COMEÇAM em [start, end), por início e depois por id (ordem estável). */
	@Query("select a from Appointment a where a.providerId = :providerId and a.status <> "
			+ "com.queuecare.scheduling.persistence.AppointmentStatus.CANCELLED and a.startsAt >= :start and a.startsAt < :end "
			+ "order by a.startsAt asc, a.id asc")
	java.util.List<Appointment> findDayAgenda(@Param("providerId") UUID providerId, @Param("start") Instant start, @Param("end") Instant end);

	/** Linha da fila: o agendamento e o instante do check-in. */
	record QueueRow(Appointment appointment, Instant checkedInAt) {
	}

	/**
	 * Fila do prestador: agendamentos em CHECKED_IN que COMEÇAM em [start, end), na ordem de chegada (instante do evento CHECKED_IN) e,
	 * em empate, por id (ordem estável). Cada agendamento tem no máximo um evento CHECKED_IN (a transição só acontece uma vez).
	 */
	@Query("select new com.queuecare.scheduling.persistence.AppointmentRepository$QueueRow(a, e.createdAt) from Appointment a, AppointmentEvent e "
			+ "where e.appointmentId = a.id and e.eventType = com.queuecare.scheduling.persistence.AppointmentEventType.CHECKED_IN "
			+ "and a.providerId = :providerId and a.status = com.queuecare.scheduling.persistence.AppointmentStatus.CHECKED_IN "
			+ "and a.startsAt >= :start and a.startsAt < :end order by e.createdAt asc, a.id asc")
	java.util.List<QueueRow> findQueue(@Param("providerId") UUID providerId, @Param("start") Instant start, @Param("end") Instant end);

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
