-- ADR 0011: as transições do agendamento só andam para frente, então cada tipo de evento acontece NO MÁXIMO UMA VEZ por
-- agendamento (agendado, chegou, concluído, cancelado). A fila lê o evento de chegada e depende disso: sem a restrição, um
-- evento duplicado por falha de código ou escrita manual faria a mesma pessoa aparecer duas vezes. O banco garante.
ALTER TABLE appointment_events ADD CONSTRAINT appointment_events_one_per_type UNIQUE (appointment_id, event_type);
