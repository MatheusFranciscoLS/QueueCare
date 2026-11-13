# ADR 0002: controle de concorrência e sobreposição de horários

- **Status:** Accepted
- **Data:** 2026-11-13
- **Quem decidiu:** o Matheus delegou a escolha; a alternativa foi proposta por Claude e revisada pelo ChatGPT (revisor independente). O Matheus deve conseguir explicar esta decisão com as próprias palavras (ver `docs/estudo/`).

## Contexto
Dois clientes podem tentar reservar o mesmo horário ao mesmo tempo, e serviços têm durações diferentes (60 min às 10h e 30 min às 10h30 se sobrepõem). A garantia precisa existir no banco, não só na aplicação (R2 em `docs/regras.md`; cenários em `docs/cenarios-r2.md`).

## Alternativas consideradas
1. **Índice único parcial em (`provider_id`, `starts_at`) com slots fixos de 30 min.** Simples, mas só impede reservas que começam no mesmo instante; serviços de 60 min ocupam dois slots e a sobreposição passa.
2. **Tabela de slots ocupados** (um registro por slot, único por prestador e slot). Funciona com durações múltiplas do slot; mais linhas e mais código para manter consistente.
3. **Restrição de exclusão do PostgreSQL** (`EXCLUDE USING gist`) com `tstzrange(starts_at, ends_at, '[)')`, operador `&&`, extensão `btree_gist`, apenas para agendamentos ativos (cancelados fora). Bloqueia qualquer sobreposição, inclusive com durações livres.
4. **Bloqueio otimista (`@Version`) ou bloqueio pessimista (`SELECT ... FOR UPDATE`) sozinhos.** Ajudam em atualizações, mas não garantem exclusividade de um intervalo novo.

## Decisão
**Alternativa 3**, junto com validação na aplicação:
- A aplicação valida antes (dentro de uma transação) para dar mensagem clara e **HTTP 409**.
- O PostgreSQL é a garantia final: mesmo que duas requisições passem pela validação ao mesmo tempo, o banco recusa a segunda.

Forma da solução (a migration real será escrita e validada em PostgreSQL real, junto com o teste T3):
```sql
CREATE EXTENSION IF NOT EXISTS btree_gist;

ALTER TABLE appointments
  ADD CONSTRAINT appointments_sem_sobreposicao
  EXCLUDE USING gist (
    provider_id WITH =,                              -- mesmo prestador
    tstzrange(starts_at, ends_at, '[)') WITH &&      -- intervalos que se sobrepõem
  )
  WHERE (status <> 'CANCELLED');                     -- cancelados não ocupam a agenda
```
Em palavras: "no banco, dois agendamentos do mesmo prestador, não cancelados, não podem ter intervalos que se sobreponham".

Cuidado: a coluna `status` deve ser `NOT NULL`. Com `status` nulo, a condição `status <> 'CANCELLED'` não é verdadeira e a linha escaparia da proteção. Também convém um CHECK `ends_at > starts_at`.

## Por que não as outras
- Alternativa 1 e 2 exigem horários fixos e deixam brechas ou mais código para manter.
- Alternativa 4 não protege a criação de um intervalo novo.

## Consequências
- Migration com `CREATE EXTENSION btree_gist` (disponível no PostgreSQL padrão; H2 não tem, então esta regra só se testa com PostgreSQL real).
- A violação da constraint de exclusão deve ser traduzida para **HTTP 409** **só quando for essa constraint** (não qualquer `DataIntegrityViolationException`), e depois do rollback: no PostgreSQL a transação que falhou fica abortada. Com JPA, o erro pode aparecer no `flush()` ou no `commit`, não necessariamente no `save()`.
- **Dois códigos de erro para quem perde a disputa (provado no T3):** quando duas transações inserem intervalos conflitantes ao mesmo tempo, o PostgreSQL devolve `23P01` (exclusion_violation) ou, se elas se esperam mutuamente, aborta uma por deadlock com `40P01` (deadlock_detected). Nos dois casos só uma reserva é gravada. A aplicação deve traduzir **ambos** em HTTP 409 (e nenhum outro erro de banco).
- Teste T3 com PostgreSQL real (Testcontainers ou PostgreSQL nativo, mesma versão principal do CI): duas threads, conexões independentes, **sem** `@Transactional` envolvendo o teste inteiro; no fim, só um agendamento ativo no banco.
- Vale também para o CI: o PostgreSQL será um serviço do GitHub Actions quando o T3 existir.
