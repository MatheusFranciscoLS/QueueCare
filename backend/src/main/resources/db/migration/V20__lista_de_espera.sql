-- ADR 0022: lista de espera conduzida pela equipe. O tutor pede "avise-me se vagar um horário" num dia; a equipe vê quem espera e chama pelo
-- WhatsApp. Sem envio automático. A entrada guarda só ids e a data pedida; nome e telefone vêm dos dados atuais, com as regras da agenda.
-- Remover não apaga: removed_at marca a saída (o tutor saiu, a equipe tirou), e o índice único parcial vale só para entradas ativas.
CREATE TABLE waitlist_entries (
    id          uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    provider_id uuid        NOT NULL REFERENCES providers (id) ON DELETE RESTRICT,
    service_id  uuid        NOT NULL REFERENCES services (id) ON DELETE RESTRICT,
    customer_id uuid        NOT NULL REFERENCES users (id) ON DELETE RESTRICT,
    pet_id      uuid        NOT NULL,
    wanted_date date        NOT NULL,
    created_at  timestamptz NOT NULL DEFAULT now(),
    called_at   timestamptz,
    removed_at  timestamptz,
    -- O pet tem de ser do mesmo cliente (a mesma chave composta dos agendamentos).
    CONSTRAINT waitlist_pet_fk FOREIGN KEY (pet_id, customer_id) REFERENCES pets (id, customer_id) ON DELETE RESTRICT
);

-- Uma entrada ATIVA por tutor, estúdio, serviço, pet e dia.
CREATE UNIQUE INDEX waitlist_active_uk ON waitlist_entries (customer_id, provider_id, service_id, pet_id, wanted_date) WHERE removed_at IS NULL;
-- A equipe lê a lista de um dia de um estúdio por ordem de entrada.
CREATE INDEX waitlist_day_idx ON waitlist_entries (provider_id, wanted_date, created_at) WHERE removed_at IS NULL;
