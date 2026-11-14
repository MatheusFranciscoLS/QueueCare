-- Administração de usuários (ADR 0007): ativar e desativar contas.

-- Linha única de controle: quem ativa ou desativa contas a trava (SELECT ... FOR UPDATE) antes de decidir, então dois ADMIN
-- que se desativam ao mesmo tempo são atendidos um de cada vez e a regra "sempre sobra um ADMIN ativo" vale sob concorrência.
-- Não é usuário nem segredo; é só o ponto de serialização.
CREATE TABLE admin_control (
    id smallint PRIMARY KEY,
    CONSTRAINT admin_control_single_row CHECK (id = 1)
);
INSERT INTO admin_control (id) VALUES (1);

-- Duas ações novas na auditoria (somente de acréscimo, sem segredos).
ALTER TABLE audit_log DROP CONSTRAINT audit_log_action_check;
ALTER TABLE audit_log ADD CONSTRAINT audit_log_action_check CHECK (action IN (
    'ADMIN_BOOTSTRAPPED', 'PROVIDER_ACCOUNT_CREATED', 'ATTENDANT_ACCOUNT_CREATED', 'PASSWORD_CHANGED', 'PASSWORD_RESET',
    'USER_DEACTIVATED', 'USER_ACTIVATED'));
