-- ADR 0033 (riscos): o tutor cadastrado pelo balcão não tem acesso ao app, então não consegue exportar nem excluir os próprios dados sozinho. O dono do estúdio atende o pedido
-- por ele, e as duas ações deixam rastro. Só EXPANDE a lista de ações aceitas (compatível com a versão anterior da API).
ALTER TABLE audit_log DROP CONSTRAINT audit_log_action_check;
ALTER TABLE audit_log ADD CONSTRAINT audit_log_action_check CHECK (action IN (
    'ADMIN_BOOTSTRAPPED', 'PROVIDER_ACCOUNT_CREATED', 'ATTENDANT_ACCOUNT_CREATED', 'PASSWORD_CHANGED', 'PASSWORD_RESET',
    'USER_DEACTIVATED', 'USER_ACTIVATED', 'CUSTOMER_PASSWORD_RESET_BY_STUDIO', 'ACCOUNT_ANONYMIZED', 'CUSTOMER_CREATED_BY_STUDIO',
    'CUSTOMER_EXPORTED_BY_STUDIO', 'CUSTOMER_ANONYMIZED_BY_STUDIO'));
