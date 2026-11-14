-- Ciclo de credenciais (ADR 0006): troca de senha pelo próprio usuário e redefinição pelo ADMIN.

-- Versão das credenciais: o token carrega a versão com que foi emitido; troca ou redefinição de senha INCREMENTA a versão,
-- e todo token anterior (inclusive um roubado) deixa de valer na hora. Exato, sem depender de relógio nem de segundos.
ALTER TABLE users ADD COLUMN token_version integer NOT NULL DEFAULT 0;

-- Duas ações novas na auditoria (a tabela continua somente de acréscimo, sem segredos).
ALTER TABLE audit_log DROP CONSTRAINT audit_log_action_check;
ALTER TABLE audit_log ADD CONSTRAINT audit_log_action_check CHECK (action IN (
    'ADMIN_BOOTSTRAPPED', 'PROVIDER_ACCOUNT_CREATED', 'ATTENDANT_ACCOUNT_CREATED', 'PASSWORD_CHANGED', 'PASSWORD_RESET'));
