-- Demonstração pública (ADR 0041): guarda quando os dados fictícios foram repostos pela última vez (uma linha só).
-- Tabela nova e vazia: a versão anterior da API ignora a tabela, então a migração é segura com a API antiga ainda no ar (expandir e contrair).
CREATE TABLE demo_state (
    id       smallint    PRIMARY KEY CHECK (id = 1),
    reset_at timestamptz NOT NULL
);
