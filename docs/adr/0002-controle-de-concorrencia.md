# ADR 0002: controle de concorrência e sobreposição de horários

- **Status:** Proposed (o Matheus decide e muda para Accepted na semana 1)
- **Data:** 2026-11-13

## Contexto
Dois clientes podem tentar reservar o mesmo horário ao mesmo tempo, e serviços têm durações diferentes (60 min às 10h e 30 min às 10h30 se sobrepõem). A garantia precisa existir no banco, não só na aplicação (R2 em `docs/regras.md`).

## Alternativas consideradas
1. **Índice único parcial em (`provider_id`, `starts_at`) com slots fixos de 30 min.** Simples, mas só impede reservas que começam no mesmo instante; serviços de 60 min ocupam dois slots e a sobreposição passa.
2. **Tabela de slots ocupados** (um registro por slot, único por prestador e slot). Funciona com durações múltiplas do slot; mais linhas e mais código para manter consistente.
3. **Restrição de exclusão do PostgreSQL** (`EXCLUDE USING gist`) com `tstzrange(starts_at, ends_at, '[)')`, operador `&&`, extensão `btree_gist`, apenas para agendamentos ativos (cancelados fora). Bloqueia qualquer sobreposição, inclusive com durações livres.
4. **Bloqueio otimista (`@Version`) ou bloqueio pessimista (`SELECT ... FOR UPDATE`) sozinhos.** Ajudam em atualizações, mas não garantem exclusividade de um intervalo novo.

## Decisão
(a preencher pelo Matheus. Preferência inicial: alternativa 3, com validação transacional na aplicação para dar mensagem clara e HTTP 409, e a constraint como garantia final.)

## Consequências
(a preencher: migration com `CREATE EXTENSION btree_gist`; como o Spring traduz a violação em 409; teste de concorrência T3 com PostgreSQL real via Testcontainers, duas threads, uma vence e a outra recebe conflito.)
