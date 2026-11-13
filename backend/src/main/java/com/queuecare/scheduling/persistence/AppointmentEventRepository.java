package com.queuecare.scheduling.persistence;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AppointmentEventRepository extends JpaRepository<AppointmentEvent, UUID> {

	List<AppointmentEvent> findByAppointmentId(UUID appointmentId);
}
