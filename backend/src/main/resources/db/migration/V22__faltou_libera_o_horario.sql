-- ADR 0021 (limitação da fatia 2): quem faltou não ocupa mais o horário. Se a pessoa falta no começo de um atendimento longo, o resto do horário volta
-- para a agenda (a equipe pode encaixar alguém). A trava contra reserva dupla (ADR 0002) agora ignora CANCELLED e NO_SHOW.
-- O nome da restrição é o mesmo (o tradutor de conflitos de horário a reconhece por ele).
ALTER TABLE appointments DROP CONSTRAINT appointments_no_overlap;
ALTER TABLE appointments
    ADD CONSTRAINT appointments_no_overlap EXCLUDE USING gist (
        provider_id WITH =,
        tstzrange(starts_at, ends_at, '[)') WITH &&
    ) WHERE (status NOT IN ('CANCELLED', 'NO_SHOW'));
