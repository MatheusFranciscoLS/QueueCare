-- ADR 0016 (item 2, PR 49): todo agendamento NOVO tem pet; os que já existiam continuam sem.
--
-- A marca "legacy_without_pet" NÃO depende de data nem de relógio (um created_at informável permitiria retroagir a data e escapar da
-- regra): ela é escrita SÓ por esta migration, nas linhas que já existem sem pet. Um gatilho impede que alguém declare uma linha nova
-- como antiga (INSERT com a marca verdadeira) e que alguém mude a marca de uma linha existente (UPDATE). A entidade JPA não mapeia a
-- coluna, então a aplicação nunca a escreve nem a lê.
ALTER TABLE appointments ADD COLUMN legacy_without_pet boolean NOT NULL DEFAULT false;

-- Linhas já existentes sem pet: marcadas agora, antes de a regra e o gatilho existirem.
UPDATE appointments SET legacy_without_pet = true WHERE pet_id IS NULL;

ALTER TABLE appointments
    ADD CONSTRAINT appointments_pet_required_check CHECK (pet_id IS NOT NULL OR legacy_without_pet);

CREATE FUNCTION appointments_legacy_guard() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'INSERT' AND NEW.legacy_without_pet THEN
        RAISE EXCEPTION 'agendamento novo nao pode ser marcado como antigo (legacy_without_pet)' USING ERRCODE = '23514';
    END IF;
    IF TG_OP = 'UPDATE' AND NEW.legacy_without_pet IS DISTINCT FROM OLD.legacy_without_pet THEN
        RAISE EXCEPTION 'legacy_without_pet nao pode ser alterado' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER appointments_legacy_guard BEFORE INSERT OR UPDATE ON appointments
    FOR EACH ROW EXECUTE FUNCTION appointments_legacy_guard();
