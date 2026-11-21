package com.queuecare.scheduling.persistence;

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AppointmentRepository extends JpaRepository<Appointment, UUID> {

	/**
	 * Atualiza a cópia dos dados do pet nos agendamentos que AINDA estão SCHEDULED (ADR 0016, item 2); CHECKED_IN, COMPLETED e CANCELLED
	 * nunca mudam. É uma consulta em massa: não atualiza entidades já carregadas, por isso limpa o contexto de persistência depois de
	 * gravar o que estava pendente; e SOBE o version, para que um check-in ou cancelamento que tenha lido o agendamento antes da edição
	 * seja recusado (bloqueio otimista) em vez de gravar por cima. O {@code status} é reavaliado depois de esperar o bloqueio da linha:
	 * se um check-in vencer a corrida, a cópia fica como estava. Devolve quantos agendamentos mudaram.
	 */
	@org.springframework.data.jpa.repository.Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("update Appointment a set a.petName = :name, a.petSpecies = :species, a.petBreed = :breed, a.petSize = :size, a.petNotes = :notes, "
			+ "a.petCoat = :coat, a.petWeightKg = :weight, a.petTemperament = :temperament, a.petHealthNotes = :healthNotes, a.petVaccinated = :vaccinated, "
			+ "a.version = a.version + 1 where a.petId = :petId and a.status = com.queuecare.scheduling.persistence.AppointmentStatus.SCHEDULED")
	int refreshPetSnapshot(@Param("petId") UUID petId, @Param("name") String name, @Param("species") com.queuecare.pets.PetSpecies species,
			@Param("breed") String breed, @Param("size") com.queuecare.pets.PetSize size, @Param("notes") String notes,
			@Param("coat") com.queuecare.pets.PetCoat coat, @Param("weight") java.math.BigDecimal weight,
			@Param("temperament") com.queuecare.pets.PetTemperament temperament, @Param("healthNotes") String healthNotes,
			@Param("vaccinated") Boolean vaccinated);

	java.util.List<Appointment> findByCustomerId(UUID customerId, org.springframework.data.domain.Pageable pageable);

	/** Visitas de UM pet do cliente (ADR 0019, fatia 6): todas as situações, a mais recente primeiro (ordem estável por id). */
	@Query("select a from Appointment a where a.petId = :petId and a.customerId = :customerId order by a.startsAt desc, a.id desc")
	java.util.List<Appointment> findPetVisits(@Param("petId") UUID petId, @Param("customerId") UUID customerId, org.springframework.data.domain.Pageable pageable);

	/** Atendimentos CONCLUÍDOS do pet neste prestador, antes do instante dado, o mais recente primeiro: o que a equipe vê como "visitas anteriores". */
	@Query("select a from Appointment a where a.petId = :petId and a.providerId = :providerId and a.status = "
			+ "com.queuecare.scheduling.persistence.AppointmentStatus.COMPLETED and a.startsAt < :before order by a.startsAt desc, a.id desc")
	java.util.List<Appointment> findCompletedVisits(@Param("petId") UUID petId, @Param("providerId") UUID providerId, @Param("before") Instant before,
			org.springframework.data.domain.Pageable pageable);

	/** Agenda do dia: agendamentos NÃO cancelados do prestador que COMEÇAM em [start, end), por início e depois por id (ordem estável). */
	@Query("select a from Appointment a where a.providerId = :providerId and a.status <> "
			+ "com.queuecare.scheduling.persistence.AppointmentStatus.CANCELLED and a.startsAt >= :start and a.startsAt < :end "
			+ "order by a.startsAt asc, a.id asc")
	java.util.List<Appointment> findDayAgenda(@Param("providerId") UUID providerId, @Param("start") Instant start, @Param("end") Instant end);

	/** Faltas de um tutor num prestador (ADR 0021): uma linha por tutor que TEM falta, dentre os pedidos. */
	record NoShowCount(UUID customerId, long count) {
	}

	@Query("select new com.queuecare.scheduling.persistence.AppointmentRepository$NoShowCount(a.customerId, count(a)) from Appointment a where a.providerId = :providerId "
			+ "and a.status = com.queuecare.scheduling.persistence.AppointmentStatus.NO_SHOW and a.customerId in :customerIds group by a.customerId")
	java.util.List<NoShowCount> countNoShows(@Param("providerId") UUID providerId, @Param("customerIds") java.util.Collection<UUID> customerIds);

	/** Linha da fila: o agendamento e o instante do check-in. */
	record QueueRow(Appointment appointment, Instant checkedInAt) {
	}

	/**
	 * Fila do prestador: agendamentos que já chegaram e ainda não foram retirados (CHECKED_IN, IN_PROGRESS e READY) e COMEÇAM em [start, end), na ordem de chegada (instante do evento CHECKED_IN) e,
	 * em empate, por id (ordem estável). Cada agendamento tem no máximo um evento CHECKED_IN (a transição só acontece uma vez).
	 */
	@Query("select new com.queuecare.scheduling.persistence.AppointmentRepository$QueueRow(a, e.createdAt) from Appointment a, AppointmentEvent e "
			+ "where e.appointmentId = a.id and e.eventType = com.queuecare.scheduling.persistence.AppointmentEventType.CHECKED_IN "
			+ "and a.providerId = :providerId and a.status in (com.queuecare.scheduling.persistence.AppointmentStatus.CHECKED_IN, com.queuecare.scheduling.persistence.AppointmentStatus.IN_PROGRESS, "
			+ "com.queuecare.scheduling.persistence.AppointmentStatus.READY) "
			+ "and a.startsAt >= :start and a.startsAt < :end order by e.createdAt asc, a.id asc")
	java.util.List<QueueRow> findQueue(@Param("providerId") UUID providerId, @Param("start") Instant start, @Param("end") Instant end);

	/** Agendamentos ATIVOS (não cancelados) do prestador que se sobrepõem a [start, end): base da consulta de horários livres. */
	@Query("select a from Appointment a where a.providerId = :providerId and a.status <> "
			+ "com.queuecare.scheduling.persistence.AppointmentStatus.CANCELLED and a.startsAt < :end and a.endsAt > :start")
	java.util.List<Appointment> findActiveOverlapping(@Param("providerId") UUID providerId, @Param("start") Instant start, @Param("end") Instant end);

	/** Como {@link #existsActiveOverlapping}, ignorando um agendamento (o próprio, ao remarcar: ele não conflita consigo mesmo). */
	@Query("select count(a) > 0 from Appointment a where a.providerId = :providerId and a.id <> :ignoring and a.status <> "
			+ "com.queuecare.scheduling.persistence.AppointmentStatus.CANCELLED and a.startsAt < :end and a.endsAt > :start")
	boolean existsActiveOverlappingExcept(@Param("providerId") UUID providerId, @Param("start") Instant start, @Param("end") Instant end, @Param("ignoring") UUID ignoring);

	/**
	 * Há agendamento ativo (não cancelado) do prestador que se sobreponha a {@code [start, end)}?
	 * Mesma condição de TimeRange.overlaps: {@code a < d} e {@code c < b}. Esta checagem só serve para dar uma
	 * mensagem clara; quem GARANTE a exclusividade, mesmo sob concorrência, é o banco (ADR 0002).
	 */
	@Query("select count(a) > 0 from Appointment a where a.providerId = :providerId and a.status <> "
			+ "com.queuecare.scheduling.persistence.AppointmentStatus.CANCELLED and a.startsAt < :end and a.endsAt > :start")
	boolean existsActiveOverlapping(@Param("providerId") UUID providerId, @Param("start") Instant start, @Param("end") Instant end);
}
