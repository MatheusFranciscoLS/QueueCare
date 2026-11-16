-- ADR 0016 (item 2): o agendamento aponta para o pet e guarda uma CÓPIA (snapshot) dos dados dele. Agenda, fila e "Meus agendamentos"
-- leem só a cópia, nunca fazem junção com pets. Enquanto o agendamento está SCHEDULED a aplicação atualiza a cópia quando o tutor edita
-- o pet (com version + 1, por causa do bloqueio otimista); depois disso a cópia nunca mais muda. Linhas antigas ficam sem pet (tudo nulo).
ALTER TABLE appointments
    ADD COLUMN pet_id      uuid,
    ADD COLUMN pet_name    varchar(80),
    ADD COLUMN pet_species varchar(20),
    ADD COLUMN pet_breed   varchar(80),
    ADD COLUMN pet_size    varchar(20),
    ADD COLUMN pet_notes   varchar(500);

-- O banco impede usar o pet de OUTRO cliente, mesmo se a aplicação errar: a chave estrangeira é composta (pet, cliente).
-- Com pet_id nulo a chave não se aplica (agendamentos sem pet).
ALTER TABLE appointments
    ADD CONSTRAINT appointments_pet_customer_fk FOREIGN KEY (pet_id, customer_id) REFERENCES pets (id, customer_id) ON DELETE RESTRICT;

-- Consistência da cópia: ou não há pet e tudo é nulo, ou há pet com nome e espécie. As mesmas restrições de formato do cadastro (V8);
-- as expressões regulares usam COLLATE "C" pelo mesmo motivo.
ALTER TABLE appointments
    ADD CONSTRAINT appointments_pet_snapshot_check CHECK (
        (pet_id IS NULL AND pet_name IS NULL AND pet_species IS NULL AND pet_breed IS NULL AND pet_size IS NULL AND pet_notes IS NULL)
        OR (pet_id IS NOT NULL AND pet_name IS NOT NULL AND pet_species IS NOT NULL)),
    ADD CONSTRAINT appointments_pet_name_check CHECK (pet_name IS NULL OR (char_length(pet_name) BETWEEN 1 AND 80 AND pet_name = btrim(pet_name)
        AND pet_name COLLATE "C" !~ '[\u0001-\u001F\u007F-\u009F]' AND pet_name COLLATE "C" !~ '  ')),
    ADD CONSTRAINT appointments_pet_species_check CHECK (pet_species IS NULL OR pet_species IN ('DOG', 'CAT')),
    ADD CONSTRAINT appointments_pet_size_check CHECK (pet_size IS NULL OR pet_size IN ('SMALL', 'MEDIUM', 'LARGE')),
    ADD CONSTRAINT appointments_pet_breed_check CHECK (pet_breed IS NULL OR (char_length(pet_breed) BETWEEN 1 AND 80 AND pet_breed = btrim(pet_breed)
        AND pet_breed COLLATE "C" !~ '[\u0001-\u001F\u007F-\u009F]' AND pet_breed COLLATE "C" !~ '  ')),
    ADD CONSTRAINT appointments_pet_notes_check CHECK (pet_notes IS NULL OR (char_length(pet_notes) BETWEEN 1 AND 500 AND pet_notes = btrim(pet_notes)
        AND pet_notes COLLATE "C" !~ '[\u0001-\u0009\u000B-\u001F\u007F-\u009F]'
        AND pet_notes COLLATE "C" !~ '^[ \u000A]' AND pet_notes COLLATE "C" !~ '[ \u000A]$'));

-- Atualizar a cópia dos agendamentos SCHEDULED de um pet (edição do cadastro) e a chave estrangeira usam este índice.
CREATE INDEX appointments_pet_idx ON appointments (pet_id) WHERE pet_id IS NOT NULL;
