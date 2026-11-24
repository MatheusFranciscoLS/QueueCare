-- ADR 0033: cadastro e reserva pelo balcão. O estúdio cadastra o tutor que liga ou chama no WhatsApp e agenda por ele.
-- Só EXPANDE (coluna nova, anulável): a versão anterior da API continua funcionando com o banco já migrado (regra de expandir e contrair, docs/operacao.md).
-- created_by_provider_id: o estúdio que cadastrou a conta. É a ÚNICA ligação que dá à equipe o direito de agir (cadastrar pets e reservar) por esse tutor;
-- conta que o próprio tutor criou nunca tem este campo e nunca é alcançada por essas rotas.
ALTER TABLE users ADD COLUMN created_by_provider_id uuid REFERENCES providers (id);
CREATE INDEX users_created_by_provider_idx ON users (created_by_provider_id) WHERE created_by_provider_id IS NOT NULL;

ALTER TABLE audit_log DROP CONSTRAINT audit_log_action_check;
ALTER TABLE audit_log ADD CONSTRAINT audit_log_action_check CHECK (action IN (
    'ADMIN_BOOTSTRAPPED', 'PROVIDER_ACCOUNT_CREATED', 'ATTENDANT_ACCOUNT_CREATED', 'PASSWORD_CHANGED', 'PASSWORD_RESET',
    'USER_DEACTIVATED', 'USER_ACTIVATED', 'CUSTOMER_PASSWORD_RESET_BY_STUDIO', 'ACCOUNT_ANONYMIZED', 'CUSTOMER_CREATED_BY_STUDIO'));
