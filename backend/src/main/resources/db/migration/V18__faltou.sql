-- ADR 0021 (fatia 2): "faltou" (no-show). Estado final, só pela equipe, só para quem estava SCHEDULED e só a partir do horário marcado.
-- Mantém o horário ocupado no banco (a restrição de exclusão só ignora CANCELLED): o horário já passou ou está passando, então nada novo cabe nele.
ALTER TABLE appointments DROP CONSTRAINT appointments_status_check;
ALTER TABLE appointments
    ADD CONSTRAINT appointments_status_check CHECK (status IN ('SCHEDULED', 'CHECKED_IN', 'IN_PROGRESS', 'READY', 'COMPLETED', 'CANCELLED', 'NO_SHOW'));

ALTER TABLE appointment_events DROP CONSTRAINT appointment_events_type_check;
ALTER TABLE appointment_events
    ADD CONSTRAINT appointment_events_type_check CHECK (event_type IN ('SCHEDULED', 'CANCELLED', 'CHECKED_IN', 'STARTED', 'READY', 'COMPLETED', 'RESCHEDULED', 'NO_SHOW'));

-- A contagem de faltas por tutor, em cada estúdio, lê só agendamentos faltosos: índice parcial pequeno.
CREATE INDEX appointments_no_show_idx ON appointments (provider_id, customer_id) WHERE status = 'NO_SHOW';
