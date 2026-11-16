-- ADR 0015: página pública do estúdio. O endereço público (slug) é único, em minúsculas, e só um estúdio PUBLICADO aparece.
ALTER TABLE providers
    ADD COLUMN slug      varchar(40),
    ADD COLUMN published boolean NOT NULL DEFAULT false;

-- Formato: minúsculas, números e hífens entre grupos (sem hífen no começo, no fim nem duplo), de 3 a 40 caracteres.
ALTER TABLE providers
    ADD CONSTRAINT providers_slug_format CHECK (slug IS NULL OR (slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$' AND char_length(slug) BETWEEN 3 AND 40)),
    ADD CONSTRAINT providers_published_needs_slug CHECK (NOT published OR slug IS NOT NULL);

CREATE UNIQUE INDEX providers_slug_key ON providers (slug) WHERE slug IS NOT NULL;
