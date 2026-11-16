-- ADR 0016: Pet (animal atendido). O tutor é o CUSTOMER dono do cadastro; não há exclusão física (arquivar = active false).
-- A aplicação NORMALIZA o texto (apara, colapsa espaços, converte quebras de linha); o banco GARANTE o resultado: recusa o que
-- não está na forma normalizada, para que nenhum caminho alternativo grave dado inválido. Controles são listados por intervalo
-- explícito (\uXXXX tem sempre 4 dígitos), sem depender da localidade do banco: C0 (U+0001 a U+001F), DEL e C1 (U+007F a U+009F).
CREATE TABLE pets (
    id          uuid         PRIMARY KEY DEFAULT gen_random_uuid(),
    customer_id uuid         NOT NULL REFERENCES users (id) ON DELETE RESTRICT,
    name        varchar(80)  NOT NULL,
    species     varchar(20)  NOT NULL,
    breed       varchar(80),
    size        varchar(20),
    notes       varchar(500),
    active      boolean      NOT NULL DEFAULT true,
    created_at  timestamptz  NOT NULL DEFAULT now(),
    updated_at  timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT pets_name_check    CHECK (char_length(name) BETWEEN 1 AND 80 AND name = btrim(name)
                                         AND name !~ '[\u0001-\u001F\u007F-\u009F]' AND name !~ '  '),
    CONSTRAINT pets_species_check CHECK (species IN ('DOG', 'CAT')),
    CONSTRAINT pets_size_check    CHECK (size IS NULL OR size IN ('SMALL', 'MEDIUM', 'LARGE')),
    CONSTRAINT pets_breed_check   CHECK (breed IS NULL OR (char_length(breed) BETWEEN 1 AND 80 AND breed = btrim(breed)
                                         AND breed !~ '[\u0001-\u001F\u007F-\u009F]' AND breed !~ '  ')),
    -- Em notes só o \n (U+000A) é permitido entre os controles; isso também recusa \r, então só existe quebra de linha \n.
    -- Como btrim só apara espaços, o texto também não pode começar nem terminar com \n (a aplicação apara as duas coisas).
    CONSTRAINT pets_notes_check   CHECK (notes IS NULL OR (char_length(notes) BETWEEN 1 AND 500 AND notes = btrim(notes)
                                         AND notes !~ '[\u0001-\u0009\u000B-\u001F\u007F-\u009F]'
                                         AND notes !~ '^[ \u000A]' AND notes !~ '[ \u000A]$')),
    -- Necessário para a chave estrangeira composta de appointments (PR seguinte): o agendamento só pode usar pet do mesmo cliente.
    CONSTRAINT pets_id_customer_uk UNIQUE (id, customer_id)
);
CREATE INDEX pets_customer_idx ON pets (customer_id, active);
