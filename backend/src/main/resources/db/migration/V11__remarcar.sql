-- ADR 0017: remarcar. Remarcar MOVE o mesmo agendamento e pode acontecer várias vezes, então o evento RESCHEDULED precisa poder repetir.
--
-- A V6 (ADR 0011) impõe UM evento de cada tipo por agendamento, e a fila depende disso para CHECKED_IN. Em vez de abrir mão da regra, ela
-- passa a valer para TODOS os tipos menos RESCHEDULED (índice único parcial): agendado, chegou, concluído e cancelado continuam no máximo uma vez.
ALTER TABLE appointment_events DROP CONSTRAINT appointment_events_one_per_type;
CREATE UNIQUE INDEX appointment_events_one_per_type ON appointment_events (appointment_id, event_type) WHERE event_type <> 'RESCHEDULED';

ALTER TABLE appointment_events DROP CONSTRAINT appointment_events_type_check;
ALTER TABLE appointment_events
    ADD CONSTRAINT appointment_events_type_check CHECK (event_type IN ('SCHEDULED', 'CANCELLED', 'CHECKED_IN', 'COMPLETED', 'RESCHEDULED'));

-- O evento de remarcação guarda o horário anterior e o novo (para o histórico ser legível e auditável). Nos outros tipos ficam nulos;
-- numa remarcação os dois existem e são diferentes (remarcar para o mesmo horário não é remarcar).
ALTER TABLE appointment_events
    ADD COLUMN previous_starts_at timestamptz,
    ADD COLUMN new_starts_at      timestamptz;
ALTER TABLE appointment_events
    ADD CONSTRAINT appointment_events_reschedule_check CHECK (
        CASE WHEN event_type = 'RESCHEDULED'
            THEN previous_starts_at IS NOT NULL AND new_starts_at IS NOT NULL AND previous_starts_at <> new_starts_at
            ELSE previous_starts_at IS NULL AND new_starts_at IS NULL
        END);
