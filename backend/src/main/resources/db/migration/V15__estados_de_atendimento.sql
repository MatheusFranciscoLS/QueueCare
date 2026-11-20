-- ADR 0019 (fatia 4): a máquina do atendimento ganha "em atendimento" e "pronto para retirada":
--   SCHEDULED -> CHECKED_IN -> IN_PROGRESS -> READY -> COMPLETED (retirado), só para frente; CANCELLED só antes de chegar (regra já existente).
-- Cada transição grava um evento (UNIQUE por tipo continua valendo, V6/V11): STARTED e READY são os novos tipos.
ALTER TABLE appointments DROP CONSTRAINT appointments_status_check;
ALTER TABLE appointments
    ADD CONSTRAINT appointments_status_check CHECK (status IN ('SCHEDULED', 'CHECKED_IN', 'IN_PROGRESS', 'READY', 'COMPLETED', 'CANCELLED'));

ALTER TABLE appointment_events DROP CONSTRAINT appointment_events_type_check;
ALTER TABLE appointment_events
    ADD CONSTRAINT appointment_events_type_check CHECK (event_type IN ('SCHEDULED', 'CANCELLED', 'CHECKED_IN', 'STARTED', 'READY', 'COMPLETED', 'RESCHEDULED'));
