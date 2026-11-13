# Visão geral do QueueCare (para entender o sistema inteiro)

Atualizado a cada PR. Escrito em linguagem simples; leia nesta ordem e tente contar o fluxo com as próprias palavras.

## O que o sistema faz
Pequenos prestadores de serviço (oficina, clínica, barbearia) recebem agendamentos de clientes e organizam o atendimento do dia.

## O fluxo principal (planejado; só o passo 3 existe hoje, e apenas a conta de sobreposição)
1. O **cliente** escolhe um serviço e um horário.
2. O sistema deverá conferir se o horário está **dentro do expediente** do prestador (R1) e se respeita a **antecedência mínima** e o **fuso** (R5).
3. O sistema deverá conferir se o horário **não se sobrepõe** a outro agendamento ativo do mesmo prestador (R2). A conta de sobreposição **já existe** na classe `TimeRange`.
4. O agendamento deverá ser **gravado** no PostgreSQL. A trava prevista no ADR 0002 (restrição de exclusão) deverá **recusar sobreposição** mesmo sob requisições simultâneas, e a API deverá traduzir esse conflito específico em **HTTP 409**. Isso só será considerado pronto depois de implementado e provado pelo teste T3 com PostgreSQL real.
5. O cliente deverá poder **cancelar** dentro do prazo (R3). O horário volta a ficar livre e o evento fica registrado.
6. Cada perfil só deverá acessar o que pode (R4): cliente só os próprios agendamentos; atendente administra a agenda; prestador configura serviços e horários.

## Peças técnicas
- **React + TypeScript** (`frontend/`): telas.
- **Spring Boot, Java 21** (`backend/`): regras de negócio e API.
- **PostgreSQL + Flyway**: dados e migrations (mudanças do banco só por migration).
- **CI no GitHub** (`.github/workflows/ci.yml`): compila, testa, confere o frontend e procura segredos.

## Onde estamos
| Etapa | Estado |
|---|---|
| Estrutura (backend, frontend, CI, Docker Compose) | Pronta. O Docker Compose ainda não foi testado (Docker precisa de reinício do PC). |
| R2 pura (`TimeRange`, sobreposição de intervalos) | Pronta, com 14 testes (ver `docs/estudo/r2-time-range.md`). |
| R1 disponibilidade (`AvailabilityPolicy`) | Pronta como função pura, com 27 testes (ver `docs/estudo/r1-disponibilidade.md`). Sem ligação com banco e API ainda. |
| R5 fuso e antecedência (passado, antecedência mínima, horários locais inexistentes ou ambíguos) | A fazer. |
| Entidades e migrations, teste de concorrência T3 | A fazer, depende de PostgreSQL real. |
| Autenticação e endpoints | A fazer (ADR 0003 ainda é proposta). |

## Limites honestos por enquanto
- Ainda não há API de agendamento, banco com tabelas nem tela de agendamento.
- Nenhuma proteção contra reserva dupla no banco foi **comprovada** ainda (falta o T3 com PostgreSQL real).
- O projeto foi desenvolvido com assistência substancial de IA (ver README).
