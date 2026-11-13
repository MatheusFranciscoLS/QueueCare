-- QueueCare: esquema inicial (marco M1).
-- Decisões: docs/modelo-dados.md e docs/adr/0002-controle-de-concorrencia.md.
-- Convenções: chaves UUID (gen_random_uuid nativo do PostgreSQL 13+), instantes em timestamptz (UTC),
-- "enums" como varchar + CHECK, NOT NULL explícito (um CHECK com NULL não é violado), exclusão restrita
-- em chaves estrangeiras (o histórico não some por uma operação administrativa; use desativação lógica).

CREATE EXTENSION IF NOT EXISTS btree_gist;

-- Identidade e login. O perfil CUSTOMER de appointments.customer_id é conferido na aplicação (R4).
CREATE TABLE users (
    id            uuid         PRIMARY KEY DEFAULT gen_random_uuid(),
    name          varchar(120) NOT NULL,
    email         varchar(254) NOT NULL,
    password_hash varchar(255) NOT NULL,
    role          varchar(20)  NOT NULL,
    active        boolean      NOT NULL DEFAULT true,
    created_at    timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT users_role_check CHECK (role IN ('PROVIDER', 'ATTENDANT', 'CUSTOMER'))
);
CREATE UNIQUE INDEX users_email_lower_uk ON users (lower(email));

-- Dados do negócio do prestador (relação 1:1 com users). O fuso IANA é validado na aplicação.
CREATE TABLE providers (
    id                            uuid         PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id                       uuid         NOT NULL REFERENCES users (id) ON DELETE RESTRICT,
    business_name                 varchar(160) NOT NULL,
    time_zone                     varchar(64)  NOT NULL,
    minimum_notice_minutes        integer      NOT NULL DEFAULT 0,
    cancellation_deadline_minutes integer      NOT NULL DEFAULT 0,
    CONSTRAINT providers_user_uk UNIQUE (user_id),
    CONSTRAINT providers_notice_check CHECK (minimum_notice_minutes >= 0),
    CONSTRAINT providers_cancellation_check CHECK (cancellation_deadline_minutes >= 0)
);

CREATE TABLE services (
    id               uuid         PRIMARY KEY DEFAULT gen_random_uuid(),
    provider_id      uuid         NOT NULL REFERENCES providers (id) ON DELETE RESTRICT,
    name             varchar(120) NOT NULL,
    duration_minutes integer      NOT NULL,
    active           boolean      NOT NULL DEFAULT true,
    CONSTRAINT services_duration_check CHECK (duration_minutes > 0),
    -- Necessário para a chave estrangeira composta de appointments (serviço pertence ao prestador).
    CONSTRAINT services_id_provider_uk UNIQUE (id, provider_id)
);
CREATE INDEX services_provider_idx ON services (provider_id);

-- Janelas semanais de atendimento em horário LOCAL do prestador (R1). day_of_week segue ISO: 1 = segunda ... 7 = domingo.
CREATE TABLE availability_windows (
    id          uuid     PRIMARY KEY DEFAULT gen_random_uuid(),
    provider_id uuid     NOT NULL REFERENCES providers (id) ON DELETE RESTRICT,
    day_of_week smallint NOT NULL,
    start_time  time     NOT NULL,
    end_time    time     NOT NULL,
    CONSTRAINT availability_windows_day_check CHECK (day_of_week BETWEEN 1 AND 7),
    CONSTRAINT availability_windows_time_check CHECK (end_time > start_time),
    -- Janelas do mesmo prestador e dia não podem se sobrepor; janelas coladas (fim = início) são aceitas: intervalo [).
    CONSTRAINT availability_windows_no_overlap EXCLUDE USING gist (
        provider_id WITH =,
        day_of_week WITH =,
        tsrange(date '2000-01-01' + start_time, date '2000-01-01' + end_time, '[)') WITH &&
    )
);

-- Entidade central (R2). starts_at e ends_at em UTC.
CREATE TABLE appointments (
    id          uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    provider_id uuid        NOT NULL REFERENCES providers (id) ON DELETE RESTRICT,
    service_id  uuid        NOT NULL,
    customer_id uuid        NOT NULL REFERENCES users (id) ON DELETE RESTRICT,
    starts_at   timestamptz NOT NULL,
    ends_at     timestamptz NOT NULL,
    status      varchar(20) NOT NULL DEFAULT 'SCHEDULED',
    created_at  timestamptz NOT NULL DEFAULT now(),
    version     bigint      NOT NULL DEFAULT 0,
    CONSTRAINT appointments_status_check CHECK (status IN ('SCHEDULED', 'CHECKED_IN', 'COMPLETED', 'CANCELLED')),
    CONSTRAINT appointments_interval_check CHECK (ends_at > starts_at),
    CONSTRAINT appointments_version_check CHECK (version >= 0),
    -- O serviço precisa ser do mesmo prestador do agendamento (duas FKs separadas não garantiriam isso).
    CONSTRAINT appointments_service_provider_fk FOREIGN KEY (service_id, provider_id)
        REFERENCES services (id, provider_id) ON DELETE RESTRICT,
    -- ADR 0002: dois agendamentos NÃO cancelados do mesmo prestador não podem ter intervalos [) que se sobreponham,
    -- nem sob requisições simultâneas. A restrição é imediata (checada no INSERT/UPDATE), não adiada.
    CONSTRAINT appointments_no_overlap EXCLUDE USING gist (
        provider_id WITH =,
        tstzrange(starts_at, ends_at, '[)') WITH &&
    ) WHERE (status <> 'CANCELLED')
);
CREATE INDEX appointments_provider_starts_idx ON appointments (provider_id, starts_at);
CREATE INDEX appointments_customer_starts_idx ON appointments (customer_id, starts_at);

-- Histórico (R3). actor_id é NULO quando o evento é gerado pelo sistema, sem usuário humano.
CREATE TABLE appointment_events (
    id             uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    appointment_id uuid        NOT NULL REFERENCES appointments (id) ON DELETE RESTRICT,
    actor_id       uuid        REFERENCES users (id) ON DELETE RESTRICT,
    event_type     varchar(20) NOT NULL,
    created_at     timestamptz NOT NULL DEFAULT now(),
    notes          text,
    CONSTRAINT appointment_events_type_check CHECK (event_type IN ('SCHEDULED', 'CANCELLED', 'CHECKED_IN', 'COMPLETED'))
);
CREATE INDEX appointment_events_appointment_idx ON appointment_events (appointment_id, created_at);
