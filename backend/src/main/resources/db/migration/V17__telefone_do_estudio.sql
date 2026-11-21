-- ADR 0021 (fatia 1): telefone do estúdio, para o tutor falar com ele (link do WhatsApp) quando passar do prazo de cancelamento.
-- Opcional, no MESMO formato do telefone do tutor (V12): só dígitos com DDI 55, DDD de 11 a 99 e número de 8 ou 9 dígitos.
ALTER TABLE providers
    ADD COLUMN phone varchar(13),
    ADD CONSTRAINT providers_phone_check CHECK (phone IS NULL OR phone COLLATE "C" ~ '^55[1-9][1-9][0-9]{8,9}$');
