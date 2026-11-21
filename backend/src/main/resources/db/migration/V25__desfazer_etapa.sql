-- ADR 0026: a equipe pode DESFAZER a última etapa de um atendimento em andamento (chegou, começou, ficou pronto) quando tocou sem querer.
-- O evento da etapa desfeita é removido (a fila e as regras leem "um evento de cada tipo", e a etapa pode ser refeita) e fica um evento UNDONE,
-- que pode se repetir, com quem desfez, quando e qual etapa era (em notes). O histórico mostra a correção; nada some sem rastro.
ALTER TABLE appointment_events DROP CONSTRAINT appointment_events_type_check;
ALTER TABLE appointment_events
    ADD CONSTRAINT appointment_events_type_check CHECK (event_type IN ('SCHEDULED', 'CANCELLED', 'CHECKED_IN', 'STARTED', 'READY', 'COMPLETED', 'RESCHEDULED', 'NO_SHOW', 'UNDONE'));

DROP INDEX appointment_events_one_per_type;
CREATE UNIQUE INDEX appointment_events_one_per_type ON appointment_events (appointment_id, event_type) WHERE event_type NOT IN ('RESCHEDULED', 'UNDONE');
