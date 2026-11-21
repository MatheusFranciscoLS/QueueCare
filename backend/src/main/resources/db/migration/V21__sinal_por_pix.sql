-- ADR 0023: sinal por Pix, MANUAL. O estúdio configura a chave, o valor e QUANDO pedir (padrão: nunca); o agendamento guarda o valor pedido
-- (congelado na reserva, como o preço) e a situação do sinal. Nenhuma integração com banco: a equipe marca "recebido".
ALTER TABLE providers
    ADD COLUMN pix_key       varchar(140),
    ADD COLUMN deposit_cents integer,
    ADD COLUMN deposit_rule  varchar(30) NOT NULL DEFAULT 'NEVER',
    ADD CONSTRAINT providers_deposit_rule_check CHECK (deposit_rule IN ('NEVER', 'NEW_CUSTOMERS', 'NEW_OR_REPEAT_NO_SHOW')),
    ADD CONSTRAINT providers_deposit_cents_check CHECK (deposit_cents IS NULL OR deposit_cents BETWEEN 1 AND 10000000),
    ADD CONSTRAINT providers_pix_key_check CHECK (pix_key IS NULL OR (char_length(pix_key) BETWEEN 1 AND 140 AND pix_key = btrim(pix_key))),
    -- Pedir sinal exige chave e valor: nunca existe uma regra ligada sem ter o que mostrar ao tutor.
    ADD CONSTRAINT providers_deposit_consistency CHECK (deposit_rule = 'NEVER' OR (pix_key IS NOT NULL AND deposit_cents IS NOT NULL));

ALTER TABLE appointments
    ADD COLUMN deposit_cents       integer,
    ADD COLUMN deposit_status      varchar(12) NOT NULL DEFAULT 'NONE',
    ADD COLUMN deposit_received_at timestamptz,
    ADD CONSTRAINT appointments_deposit_status_check CHECK (deposit_status IN ('NONE', 'REQUESTED', 'RECEIVED')),
    ADD CONSTRAINT appointments_deposit_cents_check CHECK (deposit_cents IS NULL OR deposit_cents BETWEEN 1 AND 10000000),
    -- Sem sinal, sem valor; com sinal, valor; e só "recebido" tem data de recebimento.
    ADD CONSTRAINT appointments_deposit_consistency CHECK ((deposit_status = 'NONE') = (deposit_cents IS NULL)
                                                           AND ((deposit_status = 'RECEIVED') = (deposit_received_at IS NOT NULL)));
