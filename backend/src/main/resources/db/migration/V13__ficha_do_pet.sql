-- ADR 0019 (fatia 2): ficha do pet. Tudo OPCIONAL (pets e agendamentos existentes continuam válidos): tipo de pelo, peso, comportamento, saúde e vacina.
-- A aplicação normaliza e o banco GARANTE o resultado (como em V8/V9): listas fechadas, faixa de peso e texto sem controles (só \n em health_notes).
ALTER TABLE pets
    ADD COLUMN coat         varchar(10),
    ADD COLUMN weight_kg    numeric(4, 1),
    ADD COLUMN temperament  varchar(10),
    ADD COLUMN health_notes varchar(300),
    ADD COLUMN vaccinated   boolean,
    ADD CONSTRAINT pets_coat_check        CHECK (coat IS NULL OR coat IN ('SHORT', 'MEDIUM', 'LONG', 'CURLY', 'DOUBLE')),
    ADD CONSTRAINT pets_weight_check      CHECK (weight_kg IS NULL OR weight_kg BETWEEN 0.5 AND 120.0),
    ADD CONSTRAINT pets_temperament_check CHECK (temperament IS NULL OR temperament IN ('CALM', 'ANXIOUS', 'REACTIVE')),
    ADD CONSTRAINT pets_health_notes_check CHECK (health_notes IS NULL OR (char_length(health_notes) BETWEEN 1 AND 300 AND health_notes = btrim(health_notes)
        AND health_notes COLLATE "C" !~ '[\u0001-\u0009\u000B-\u001F\u007F-\u009F]'
        AND health_notes COLLATE "C" !~ '^[ \u000A]' AND health_notes COLLATE "C" !~ '[ \u000A]$'));

-- A CÓPIA no agendamento (ADR 0016, item 2): o estúdio vê a ficha do dia da reserva. Mesmas regras; nula quando o agendamento não tem pet.
ALTER TABLE appointments
    ADD COLUMN pet_coat         varchar(10),
    ADD COLUMN pet_weight_kg    numeric(4, 1),
    ADD COLUMN pet_temperament  varchar(10),
    ADD COLUMN pet_health_notes varchar(300),
    ADD COLUMN pet_vaccinated   boolean,
    ADD CONSTRAINT appointments_pet_coat_check        CHECK (pet_coat IS NULL OR pet_coat IN ('SHORT', 'MEDIUM', 'LONG', 'CURLY', 'DOUBLE')),
    ADD CONSTRAINT appointments_pet_weight_check      CHECK (pet_weight_kg IS NULL OR pet_weight_kg BETWEEN 0.5 AND 120.0),
    ADD CONSTRAINT appointments_pet_temperament_check CHECK (pet_temperament IS NULL OR pet_temperament IN ('CALM', 'ANXIOUS', 'REACTIVE')),
    ADD CONSTRAINT appointments_pet_health_notes_check CHECK (pet_health_notes IS NULL OR (char_length(pet_health_notes) BETWEEN 1 AND 300
        AND pet_health_notes = btrim(pet_health_notes)
        AND pet_health_notes COLLATE "C" !~ '[\u0001-\u0009\u000B-\u001F\u007F-\u009F]'
        AND pet_health_notes COLLATE "C" !~ '^[ \u000A]' AND pet_health_notes COLLATE "C" !~ '[ \u000A]$'));

-- Sem pet, nenhum campo da cópia (nem os novos) pode existir: a restrição de consistência do V9 passa a cobrir também a ficha.
ALTER TABLE appointments DROP CONSTRAINT appointments_pet_snapshot_check;
ALTER TABLE appointments
    ADD CONSTRAINT appointments_pet_snapshot_check CHECK (
        (pet_id IS NULL AND pet_name IS NULL AND pet_species IS NULL AND pet_breed IS NULL AND pet_size IS NULL AND pet_notes IS NULL
            AND pet_coat IS NULL AND pet_weight_kg IS NULL AND pet_temperament IS NULL AND pet_health_notes IS NULL AND pet_vaccinated IS NULL)
        OR (pet_id IS NOT NULL AND pet_name IS NOT NULL AND pet_species IS NOT NULL));
