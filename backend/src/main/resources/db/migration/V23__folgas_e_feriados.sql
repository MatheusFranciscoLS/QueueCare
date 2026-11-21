-- ADR 0024: folgas, feriados e férias. O expediente semanal não sabe do 25 de dezembro nem da semana de férias: sem isso o tutor agenda num dia em que
-- o estúdio está fechado. Cada linha fecha o estúdio por dias INTEIROS, de starts_on a ends_on (inclusive), na data local do fuso do estúdio.
-- Remover apaga a linha: não há dado pessoal nem histórico a preservar (o fechamento nunca cancela agendamentos sozinho).
CREATE TABLE time_off (
    id          uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    provider_id uuid        NOT NULL REFERENCES providers (id) ON DELETE CASCADE,
    starts_on   date        NOT NULL,
    ends_on     date        NOT NULL,
    reason      varchar(80),
    created_at  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT time_off_order_ck CHECK (starts_on <= ends_on),
    CONSTRAINT time_off_reason_ck CHECK (reason IS NULL OR (length(btrim(reason)) BETWEEN 1 AND 80))
);

-- O estúdio lê o que vem pela frente e a reserva procura "este dia está dentro de algum período?".
CREATE INDEX time_off_provider_idx ON time_off (provider_id, ends_on, starts_on);
