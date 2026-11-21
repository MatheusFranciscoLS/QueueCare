-- ADR 0025: o estúdio redefine a senha de um tutor que ele atendeu (sem e-mail: custo zero). A ação entra na auditoria (somente de acréscimo, sem segredos).
ALTER TABLE audit_log DROP CONSTRAINT audit_log_action_check;
ALTER TABLE audit_log ADD CONSTRAINT audit_log_action_check CHECK (action IN (
    'ADMIN_BOOTSTRAPPED', 'PROVIDER_ACCOUNT_CREATED', 'ATTENDANT_ACCOUNT_CREATED', 'PASSWORD_CHANGED', 'PASSWORD_RESET',
    'USER_DEACTIVATED', 'USER_ACTIVATED', 'CUSTOMER_PASSWORD_RESET_BY_STUDIO'));

-- "Este tutor já foi atendido por este estúdio?" e a busca de clientes do estúdio olham os agendamentos por estúdio e tutor.
CREATE INDEX appointments_provider_customer_idx ON appointments (provider_id, customer_id);
