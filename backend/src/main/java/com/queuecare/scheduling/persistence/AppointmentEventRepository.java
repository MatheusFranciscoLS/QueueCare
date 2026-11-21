package com.queuecare.scheduling.persistence;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AppointmentEventRepository extends JpaRepository<AppointmentEvent, UUID> {

	List<AppointmentEvent> findByAppointmentId(UUID appointmentId);

	/** Histórico em ordem determinística: instante e, em empate, id (ADR 0017, item 9). */
	List<AppointmentEvent> findByAppointmentIdOrderByCreatedAtAscIdAsc(UUID appointmentId);

	/** Remove o evento de um tipo que acontece uma vez (desfazer etapa, ADR 0026); grava o que estava pendente antes. */
	@Modifying(flushAutomatically = true)
	@Query("delete from AppointmentEvent e where e.appointmentId = :appointmentId and e.eventType = :eventType")
	int deleteByAppointmentIdAndEventType(@Param("appointmentId") UUID appointmentId, @Param("eventType") AppointmentEventType eventType);

	long countByAppointmentIdAndEventType(UUID appointmentId, AppointmentEventType eventType);
}
