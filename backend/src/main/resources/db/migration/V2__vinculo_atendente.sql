-- Vínculo ATENDENTE -> PRESTADOR (ADR 0004).
-- Regras garantidas NO BANCO (defesa em profundidade; a aplicação também valida):
--  * só usuário com perfil ATTENDANT pode ser vinculado (FK composta user_id + role -> users(id, role));
--  * enquanto houver vínculo, o perfil do usuário não pode mudar nem o usuário pode ser apagado (restrição);
--  * um atendente pertence a NO MÁXIMO UM prestador no MVP (UNIQUE em user_id);
--  * prestador com atendentes vinculados não pode ser apagado.

-- Chave referenciada composta (id + role) para a FK abaixo.
ALTER TABLE users ADD CONSTRAINT users_id_role_uk UNIQUE (id, role);

CREATE TABLE provider_staff (
    id          uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    provider_id uuid        NOT NULL REFERENCES providers (id) ON DELETE RESTRICT,
    user_id     uuid        NOT NULL,
    -- Redundante de propósito: necessário para a FK composta; o código sempre grava ATTENDANT.
    role        varchar(20) NOT NULL DEFAULT 'ATTENDANT',
    created_at  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT provider_staff_user_uk UNIQUE (user_id),
    CONSTRAINT provider_staff_role_check CHECK (role = 'ATTENDANT'),
    CONSTRAINT provider_staff_user_role_fk FOREIGN KEY (user_id, role)
        REFERENCES users (id, role) ON DELETE RESTRICT ON UPDATE RESTRICT
);
CREATE INDEX provider_staff_provider_idx ON provider_staff (provider_id);
