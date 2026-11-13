# Estudo: banco de dados e prova da reserva dupla, T3 (PR 9)

## Em linguagem simples
**Problema:** garantir, no próprio banco, que dois agendamentos do mesmo prestador nunca se sobreponham, mesmo se dois clientes clicarem ao mesmo tempo.

**Solução:** a migration `V1__schema_inicial.sql` cria as 6 tabelas (`users`, `providers`, `services`, `availability_windows`, `appointments`, `appointment_events`) e uma **trava no banco** (restrição de exclusão do PostgreSQL, ADR 0002): "para o mesmo prestador, dois agendamentos não cancelados não podem ter intervalos `[início, fim)` que se sobreponham".

**Prova:** os testes rodam num **PostgreSQL 16 de verdade** (embutido, sem Docker). Eles tentam todos os cenários da R2 e, no teste **T3**, várias conexões tentam reservar o mesmo horário no mesmo instante: **só uma vence**, no banco fica uma única reserva.

## O que o banco garante (além da R2)
- Serviço do agendamento precisa ser **do mesmo prestador** (chave estrangeira composta).
- Campos obrigatórios com `NOT NULL` (um `CHECK` com valor nulo não reprova, por isso o `NOT NULL` explícito).
- Duração/horário válidos (`fim > início`), e-mail único sem diferenciar maiúsculas, janelas de atendimento do mesmo dia sem sobreposição (coladas são aceitas).
- Nada some por engano: apagar usuário, prestador ou serviço com histórico é recusado (usar desativação).

## Um detalhe real que o T3 descobriu
Quem perde a disputa nem sempre recebe o erro "conflito de horário" (`23P01`): às vezes o PostgreSQL desfaz uma das transações por **deadlock** (`40P01`), porque as duas ficam esperando uma à outra. Nos dois casos **só uma reserva é gravada**. A aplicação (PR seguinte) deve tratar os dois como conflito (HTTP 409) e nenhum outro erro do banco.

## Onde está
- `backend/src/main/resources/db/migration/V1__schema_inicial.sql`
- `backend/src/test/java/com/queuecare/database/`: `AppointmentOverlapConstraintTest` (19), `SchemaConstraintsTest` (11), `ConcurrentBookingT3Test` (5), `PostgresTestSupport`/`DatabaseTestBase` (apoio)

## 3 perguntas de entrevista (responda com as suas palavras)
1. Por que checar na aplicação "o horário está livre?" antes de gravar não impede reserva dupla, e o que impede?
   *Pista: duas requisições checam ao mesmo tempo.*
2. Por que o teste de concorrência não pode estar dentro de uma única transação do teste?
   *Pista: seria uma só conexão; não haveria disputa real.*
3. Por que o banco pode devolver dois erros diferentes para quem perde (23P01 e 40P01), e como a API deve reagir?
   *Pista: deadlock detectado e HTTP 409.*

## Limitações e o que ainda falta
- O PostgreSQL embutido é um servidor real, mas não é a imagem Docker usada em produção; a paridade com Docker/Testcontainers pode ser acrescentada depois.
- Ainda não há JPA, serviço de agendamento nem API (PR seguinte): a aplicação ainda não usa o banco.
- O fuso do prestador (`time_zone`) é validado só na aplicação.
