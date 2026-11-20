-- ADR 0019 (fatia 1): telefone do usuário, usado pela equipe para avisar o tutor (link do WhatsApp). Opcional: contas antigas não têm.
-- Formato ÚNICO garantido pelo banco: só dígitos, com DDI 55, DDD de 11 a 99 e número de 8 ou 9 dígitos (12 ou 13 dígitos no total).
-- A aplicação aceita máscara e telefone sem DDI e normaliza para este formato; o banco recusa qualquer outro caminho.
ALTER TABLE users
    ADD COLUMN phone varchar(13),
    ADD CONSTRAINT users_phone_check CHECK (phone IS NULL OR phone COLLATE "C" ~ '^55[1-9][1-9][0-9]{8,9}$');
