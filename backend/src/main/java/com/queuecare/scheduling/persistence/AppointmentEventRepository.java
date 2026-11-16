package com.queuecare.scheduling.persistence;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AppointmentEventRepository extends JpaRepository<AppointmentEvent, UUID> {

	List<AppointmentEvent> findByAppointmentId(UUID appointmentId);

	/** Histórico em ordem determinística: instante e, em empate, id (ADR 0017, item 9). */
	List<AppointmentEvent> findByAppointmentIdOrderByCreatedAtAscIdAsc(UUID appointmentId);

	long countByAppointmentIdAndEventType(UUID appointmentId, AppointmentEventType eventType);
}
