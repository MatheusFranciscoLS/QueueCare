-- ADR 0027: LGPD mínima. (1) O tutor registra que LEU a política de privacidade (versão e instante), (2) pode exportar os próprios dados e (3) pode excluir a conta, que é
-- ANONIMIZADA no lugar (a linha continua, porque agendamentos e auditoria apontam para ela; o que identifica a pessoa sai).
ALTER TABLE users ADD COLUMN privacy_policy_version varchar(20);
ALTER TABLE users ADD COLUMN privacy_accepted_at timestamptz;
ALTER TABLE users ADD COLUMN anonymized_at timestamptz;
-- Quem aceitou uma versão tem o instante; quem não aceitou não tem nenhum dos dois.
ALTER TABLE users ADD CONSTRAINT users_privacy_pair_ck CHECK ((privacy_policy_version IS NULL) = (privacy_accepted_at IS NULL));

ALTER TABLE audit_log DROP CONSTRAINT audit_log_action_check;
ALTER TABLE audit_log ADD CONSTRAINT audit_log_action_check CHECK (action IN (
    'ADMIN_BOOTSTRAPPED', 'PROVIDER_ACCOUNT_CREATED', 'ATTENDANT_ACCOUNT_CREATED', 'PASSWORD_CHANGED', 'PASSWORD_RESET',
    'USER_DEACTIVATED', 'USER_ACTIVATED', 'CUSTOMER_PASSWORD_RESET_BY_STUDIO', 'ACCOUNT_ANONYMIZED'));
