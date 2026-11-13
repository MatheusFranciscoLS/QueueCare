# Visão geral do QueueCare (para entender o sistema inteiro)

Atualizado a cada PR. Escrito em linguagem simples; leia nesta ordem e tente contar o fluxo com as próprias palavras.

## O que o sistema faz
Pequenos prestadores de serviço (oficina, clínica, barbearia) recebem agendamentos de clientes e organizam o atendimento do dia.

## O fluxo principal (planejado; só o passo 3 existe hoje, e apenas a conta de sobreposição)
1. O **cliente** escolhe um serviço e um horário.
2. O sistema deverá conferir se o horário está **dentro do expediente** do prestador (R1) e se respeita a **antecedência mínima** e o **fuso** (R5).
3. O sistema deverá conferir se o horário **não se sobrepõe** a outro agendamento ativo do mesmo prestador (R2). A conta de sobreposição **já existe** na classe `TimeRange`.
4. O agendamento deverá ser **gravado** no PostgreSQL. A trava do ADR 0002 (restrição de exclusão) **já existe e foi provada pelo teste T3** com PostgreSQL real: recusa sobreposição mesmo sob requisições simultâneas. O serviço traduz os erros `23P01` e `40P01` em `BookingConflictException`; falta a API HTTP transformar isso em **HTTP 409**.
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
| R1 disponibilidade (`AvailabilityPolicy`) | Pronta como função pura, com 37 testes (ver `docs/estudo/r1-disponibilidade.md`). Sem ligação com banco e API ainda. |
| R5 fuso e antecedência (`BookingTimeRules`, `LocalTimeResolver`) | Pronta como função pura, com 30 testes (ver `docs/estudo/r5-fuso-antecedencia.md`). Sem ligação com API e banco ainda. |
| Banco (migration V1) e teste de concorrência T3 | Prontos e provados num PostgreSQL 16 real embutido, com 35 testes de banco (ver `docs/estudo/banco-e-t3.md`). Falta ligar a aplicação (JPA e serviço). |
| Serviço de agendamento (`BookingService`: R5 + R1 + R2 + persistência, conflito como exceção de domínio) | Pronto, com testes de integração em PostgreSQL real, inclusive concorrência (ver `docs/estudo/servico-de-agendamento.md`). Sem API HTTP ainda. |
| Autenticação e autorização por perfil (cadastro, login JWT, `/api/me`; ADR 0003 aceito) | Pronta, com 41 testes (ver `docs/estudo/autenticacao.md`). |
| API de agendar e cancelar (HTTP 409, R3 prazo de cancelamento, R4 por dono) | Pronta, com 23 testes (ver `docs/estudo/api-agendamento.md`). Sem tela. |
| Configuração do prestador (negócio, serviços, janelas) e consulta de horários livres | Pronta, com 12 testes (ver `docs/estudo/configuracao-e-horarios-livres.md`). |
| Telas (React), painel do dia, check-in e fila, vínculo do atendente ao prestador, criação de usuários PROVIDER | A fazer. |

## Limites honestos por enquanto
- Ainda não há API de agendamento, banco com tabelas nem tela de agendamento.
- A proteção contra reserva dupla está **provada no banco** (T3) e **pelo serviço de agendamento** (concorrência), mas ainda não há telas.
- O projeto foi desenvolvido com assistência substancial de IA (ver README).
