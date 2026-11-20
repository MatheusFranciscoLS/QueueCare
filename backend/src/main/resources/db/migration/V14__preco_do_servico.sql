-- ADR 0019 (fatia 3): preço do serviço. Dinheiro SEMPRE em centavos inteiros (nunca ponto flutuante). Opcional: nulo = "a combinar".
-- price_cents é o valor base (porte pequeno ou não informado); os de porte médio e grande, quando nulos, usam o base.
-- Teto de R$ 100.000,00 (10.000.000 centavos): barra erro de digitação e estouro. O banco repete a regra da aplicação.
ALTER TABLE services
    ADD COLUMN price_cents        integer,
    ADD COLUMN price_medium_cents integer,
    ADD COLUMN price_large_cents  integer,
    ADD CONSTRAINT services_price_check        CHECK (price_cents IS NULL OR price_cents BETWEEN 0 AND 10000000),
    ADD CONSTRAINT services_price_medium_check CHECK (price_medium_cents IS NULL OR price_medium_cents BETWEEN 0 AND 10000000),
    ADD CONSTRAINT services_price_large_check  CHECK (price_large_cents IS NULL OR price_large_cents BETWEEN 0 AND 10000000);

-- O valor do agendamento é CONGELADO na reserva (pelo porte do pet naquele momento): mudar o preço depois não reescreve o histórico.
-- Nulo = serviço sem preço na época (ou agendamento anterior a esta migração).
ALTER TABLE appointments
    ADD COLUMN price_cents integer,
    ADD CONSTRAINT appointments_price_check CHECK (price_cents IS NULL OR price_cents BETWEEN 0 AND 10000000);
