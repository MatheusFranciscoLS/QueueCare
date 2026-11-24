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

	/** Quantos agendamentos de um tutor neste prestador estão numa situação (ADR 0023: atendimentos concluídos e faltas). */
	long countByProviderIdAndCustomerIdAndStatus(UUID providerId, UUID customerId, AppointmentStatus status);

	/** Resumo de um tutor num estúdio (ADR 0025): atendimentos concluídos, faltas e o início do agendamento mais recente. */
	record CustomerStat(UUID customerId, long visits, long noShows, Instant lastAt) {
	}

	@Query("select new com.queuecare.scheduling.persistence.AppointmentRepository$CustomerStat(a.customerId, "
			+ "sum(case when a.status = com.queuecare.scheduling.persistence.AppointmentStatus.COMPLETED then 1L else 0L end), "
			+ "sum(case when a.status = com.queuecare.scheduling.persistence.AppointmentStatus.NO_SHOW then 1L else 0L end), max(a.startsAt)) "
			+ "from Appointment a where a.providerId = :providerId group by a.customerId")
	java.util.List<CustomerStat> customerStats(@Param("providerId") UUID providerId);

	/** Nomes de pet por tutor num estúdio (a cópia gravada em cada agendamento), sem repetir. */
	@Query("select distinct a.customerId, a.petName from Appointment a where a.providerId = :providerId and a.petName is not null")
	java.util.List<Object[]> petNamesByCustomer(@Param("providerId") UUID providerId);

	/** Anonimiza a cópia dos dados do pet gravada nos agendamentos do tutor (ADR 0027); o agendamento em si (horário, serviço, valor) continua. */
	@org.springframework.data.jpa.repository.Modifying
	@Query("update Appointment a set a.petName = 'Pet removido', a.petBreed = null, a.petNotes = null, a.petHealthNotes = null, a.petWeightKg = null where a.customerId = :customerId")
	int anonymizePetCopies(@Param("customerId") UUID customerId);

	/** Agendamentos ainda MARCADOS do tutor, de agora em diante (cancelados ao excluir a conta). */
	@Query("select a from Appointment a where a.customerId = :customerId and a.status = com.queuecare.scheduling.persistence.AppointmentStatus.SCHEDULED and a.startsAt > :now")
	java.util.List<Appointment> findFutureScheduledOf(@Param("customerId") UUID customerId, @Param("now") Instant now);

	/** O estúdio já teve algum agendamento (de qualquer estado) com este tutor? */
	boolean existsByProviderIdAndCustomerId(UUID providerId, UUID customerId);

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

	/** Agendamentos ATIVOS (nem cancelados nem faltosos: quem faltou libera o horário, ADR 0021) do prestador que se sobrepõem a [start, end): base da consulta de horários livres. */
	@Query("select a from Appointment a where a.providerId = :providerId and a.status not in ("
			+ "com.queuecare.scheduling.persistence.AppointmentStatus.CANCELLED, com.queuecare.scheduling.persistence.AppointmentStatus.NO_SHOW) and a.startsAt < :end and a.endsAt > :start")
	java.util.List<Appointment> findActiveOverlapping(@Param("providerId") UUID providerId, @Param("start") Instant start, @Param("end") Instant end);

	/** Como {@link #existsActiveOverlapping}, ignorando um agendamento (o próprio, ao remarcar: ele não conflita consigo mesmo). */
	@Query("select count(a) > 0 from Appointment a where a.providerId = :providerId and a.id <> :ignoring and a.status not in ("
			+ "com.queuecare.scheduling.persistence.AppointmentStatus.CANCELLED, com.queuecare.scheduling.persistence.AppointmentStatus.NO_SHOW) and a.startsAt < :end and a.endsAt > :start")
	boolean existsActiveOverlappingExcept(@Param("providerId") UUID providerId, @Param("start") Instant start, @Param("end") Instant end, @Param("ignoring") UUID ignoring);

	/**
	 * A MESMA reserva já feita (ADR 0035): mesmo estúdio, tutor, serviço, pet e instante de início, ainda ativa. Serve para tratar a repetição de um pedido (resposta perdida, tempo
	 * esgotado, duplo envio) como o mesmo pedido, e não como um conflito de horário com a própria reserva do tutor.
	 */
	@Query("select a from Appointment a where a.providerId = :providerId and a.customerId = :customerId and a.serviceId = :serviceId and a.petId = :petId and a.startsAt = :startsAt "
			+ "and a.status not in (com.queuecare.scheduling.persistence.AppointmentStatus.CANCELLED, com.queuecare.scheduling.persistence.AppointmentStatus.NO_SHOW) order by a.createdAt asc, a.id asc")
	java.util.List<Appointment> findSameBooking(@Param("providerId") UUID providerId, @Param("customerId") UUID customerId, @Param("serviceId") UUID serviceId, @Param("petId") UUID petId,
			@Param("startsAt") Instant startsAt);

	/**
	 * Há agendamento ativo (não cancelado) do prestador que se sobreponha a {@code [start, end)}?
	 * Mesma condição de TimeRange.overlaps: {@code a < d} e {@code c < b}. Esta checagem só serve para dar uma
	 * mensagem clara; quem GARANTE a exclusividade, mesmo sob concorrência, é o banco (ADR 0002).
	 */
	@Query("select count(a) > 0 from Appointment a where a.providerId = :providerId and a.status not in ("
			+ "com.queuecare.scheduling.persistence.AppointmentStatus.CANCELLED, com.queuecare.scheduling.persistence.AppointmentStatus.NO_SHOW) and a.startsAt < :end and a.endsAt > :start")
	boolean existsActiveOverlapping(@Param("providerId") UUID providerId, @Param("start") Instant start, @Param("end") Instant end);
}
