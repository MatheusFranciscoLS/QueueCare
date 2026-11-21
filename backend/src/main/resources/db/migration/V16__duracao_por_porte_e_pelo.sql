-- ADR 0020: duração do atendimento por porte e por pelo. Opcional: sem os campos novos, a duração é a base (duration_minutes) para todos.
-- duration_medium/large_minutes: nulos = usam a base. coat_extra_minutes: minutos somados para pelo longo, cacheado ou duplo.
-- A soma final (porte + extra de pelo) é validada na aplicação (5 a 480 minutos); o banco repete a faixa de cada campo.
ALTER TABLE services
    ADD COLUMN duration_medium_minutes integer,
    ADD COLUMN duration_large_minutes  integer,
    ADD COLUMN coat_extra_minutes      integer NOT NULL DEFAULT 0,
    ADD CONSTRAINT services_duration_medium_check CHECK (duration_medium_minutes IS NULL OR duration_medium_minutes BETWEEN 5 AND 480),
    ADD CONSTRAINT services_duration_large_check  CHECK (duration_large_minutes IS NULL OR duration_large_minutes BETWEEN 5 AND 480),
    ADD CONSTRAINT services_coat_extra_check      CHECK (coat_extra_minutes BETWEEN 0 AND 240);
