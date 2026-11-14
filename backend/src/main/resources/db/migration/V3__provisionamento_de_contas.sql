-- Provisionamento de contas (ADR 0005).
-- NENHUM usuário é inserido por migration: o ADMIN nasce só do comando de bootstrap (variáveis de ambiente).

-- 1) Novo perfil ADMIN (administra contas; NÃO acessa agendas nem dados de clientes).
ALTER TABLE users DROP CONSTRAINT users_role_check;
ALTER TABLE users ADD CONSTRAINT users_role_check CHECK (role IN ('ADMIN', 'PROVIDER', 'ATTENDANT', 'CUSTOMER'));

-- 2) Conta com senha inicial definida por outra pessoa: só pode trocar a senha até concluir a troca.
ALTER TABLE users ADD COLUMN must_change_password boolean NOT NULL DEFAULT false;

-- 3) Auditoria mínima e somente de acréscimo (append-only): quem fez, o quê, sobre quem. SEM segredos (nunca senha, hash ou token).
CREATE TABLE audit_log (
    id             uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    occurred_at    timestamptz NOT NULL DEFAULT now(),
    actor_user_id  uuid        NOT NULL REFERENCES users (id) ON DELETE RESTRICT,
    action         varchar(40) NOT NULL,
    target_user_id uuid        NOT NULL,
    target_role    varchar(20) NOT NULL,
    -- O alvo e o perfil registrado têm de ser os de um usuário real (FK composta sobre users_id_role_uk, do V2): não existe
    -- registro sem alvo nem com perfil que não confere. Consequência: o perfil de um usuário auditado não muda (RESTRICT).
    CONSTRAINT audit_log_target_fk FOREIGN KEY (target_user_id, target_role) REFERENCES users (id, role) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT audit_log_action_check CHECK (action IN ('ADMIN_BOOTSTRAPPED', 'PROVIDER_ACCOUNT_CREATED', 'ATTENDANT_ACCOUNT_CREATED')),
    CONSTRAINT audit_log_target_role_check CHECK (target_role IN ('ADMIN', 'PROVIDER', 'ATTENDANT', 'CUSTOMER'))
);
CREATE INDEX audit_log_actor_idx ON audit_log (actor_user_id, occurred_at);

-- Append-only no próprio banco: UPDATE e DELETE são recusados (a aplicação só faz INSERT). TRUNCATE é questão de privilégio
-- do papel do banco (a aplicação em produção não deve ser dona das tabelas), não de gatilho.
CREATE FUNCTION audit_log_append_only() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'audit_log é somente de acréscimo' USING ERRCODE = 'integrity_constraint_violation';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER audit_log_no_update_delete BEFORE UPDATE OR DELETE ON audit_log
    FOR EACH ROW EXECUTE FUNCTION audit_log_append_only();
