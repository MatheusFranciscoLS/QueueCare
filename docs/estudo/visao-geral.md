# Visão geral do QueueCare (para entender o sistema inteiro)

Revisado em 24/11/2026 (o estado completo e atual está no [README](../../README.md) e nos ADRs). Escrito em linguagem simples; leia nesta ordem e tente contar o fluxo com as próprias palavras.

## O que o sistema faz
O produto se chama **Pelo & Ponto**: estúdios de banho e tosa recebem agendamentos de tutores e organizam o atendimento do dia (agenda, fila de chegada, equipe, pets). O nome técnico do repositório e dos pacotes ainda é QueueCare.

## O fluxo principal (como funciona hoje)
1. O **tutor** abre a página do estúdio, escolhe o serviço, o pet, o dia e um horário livre (ADR 0015, 0016, 0020).
2. A API confere se o horário está **dentro do expediente** (R1), respeita a **antecedência mínima** e o **fuso** do estúdio (R5) e as folgas (ADR 0024).
3. Ela confere se o horário **não se sobrepõe** a outro agendamento ativo (R2). A garantia final está no **banco**: restrição de exclusão do PostgreSQL (ADR 0002), provada pelo teste T3 mesmo sob requisições simultâneas; o conflito vira **HTTP 409**.
4. O agendamento é gravado de forma **idempotente**: repetir o mesmo pedido devolve a mesma reserva (ADR 0035).
5. O tutor pode **cancelar** dentro do prazo (R3) e **remarcar** (ADR 0017). O horário volta a ficar livre e o evento fica registrado.
6. Cada perfil só acessa o que pode (R4): tutor, atendente, dono do estúdio e administrador, com a regra aplicada na API (matriz de autorização testada).
7. No dia, a equipe registra a **chegada**, acompanha a **fila** e conclui o atendimento (ADR 0011, 0026).

## Peças técnicas
- **React + TypeScript** (`frontend/`): telas.
- **Spring Boot, Java 21** (`backend/`): regras de negócio e API.
- **PostgreSQL + Flyway**: dados e migrations (mudanças do banco só por migration).
- **CI no GitHub** (`.github/workflows/ci.yml`): compila, testa (backend, frontend e navegador com Playwright), constrói a imagem do backend, ensaia a restauração do backup e procura segredos.
- **Hospedagem gratuita:** Vercel (site), Render (API) e Neon (PostgreSQL), sem cartão (ADR 0013).

## Onde estamos
A tabela abaixo é o **histórico das etapas iniciais** (cada linha com a data em que ficou pronta, tal como foi escrita na época). As etapas posteriores estão nos ADRs 0014 a 0043 e nos demais arquivos de `docs/estudo/`.

| Etapa | Estado |
|---|---|
| Estrutura (backend, frontend, CI, Docker Compose) | Pronta. (Histórico: na época o Docker Compose ainda não tinha sido testado; hoje o CI constrói a imagem do backend e a execução local usa `npm run demo` ou Docker.) |
| R2 pura (`TimeRange`, sobreposição de intervalos) | Pronta, com 14 testes (ver `docs/estudo/r2-time-range.md`). |
| R1 disponibilidade (`AvailabilityPolicy`) | Pronta como função pura, com 37 testes (ver `docs/estudo/r1-disponibilidade.md`). Sem ligação com banco e API ainda. |
| R5 fuso e antecedência (`BookingTimeRules`, `LocalTimeResolver`) | Pronta como função pura, com 30 testes (ver `docs/estudo/r5-fuso-antecedencia.md`). Sem ligação com API e banco ainda. |
| Banco (migration V1) e teste de concorrência T3 | Prontos e provados num PostgreSQL 16 real embutido, com 35 testes de banco (ver `docs/estudo/banco-e-t3.md`). Falta ligar a aplicação (JPA e serviço). |
| Serviço de agendamento (`BookingService`: R5 + R1 + R2 + persistência, conflito como exceção de domínio) | Pronto, com testes de integração em PostgreSQL real, inclusive concorrência (ver `docs/estudo/servico-de-agendamento.md`). Sem API HTTP ainda. |
| Autenticação e autorização por perfil (cadastro, login JWT, `/api/me`; ADR 0003 aceito) | Pronta, com 41 testes (ver `docs/estudo/autenticacao.md`). |
| API de agendar e cancelar (HTTP 409, R3 prazo de cancelamento, R4 por dono) | Pronta, com 23 testes (ver `docs/estudo/api-agendamento.md`). Sem tela. |
| Configuração do prestador (negócio, serviços, janelas) e consulta de horários livres | Pronta, com 14 testes (ver `docs/estudo/configuracao-e-horarios-livres.md`). |
| Vínculo atendente-prestador, cancelamento pela equipe e agenda do dia (ADR 0004) | Pronto, com testes adversários (ver `docs/estudo/vinculo-atendente-e-agenda.md`). |
| Criação controlada de usuários PROVIDER/ATTENDANT, check-in e fila, telas (React) | Pronto depois desta etapa (ADR 0005, 0011, 0025 e telas em React; ver os demais arquivos de `docs/estudo/`). |
| Remarcar, backend (ADR 0017, PR 51: V11, `POST /api/appointments/{id}/reschedule`, histórico consultável, horários livres que ignoram o próprio agendamento) | Pronto (ver `docs/estudo/remarcar-backend.md`). Faltam as telas do cliente e da equipe. |
| Pets: cadastro do tutor (ADR 0016, PR 46: V8, API `/api/customer/pets`, limites, bloqueios) | Pronto no backend, com testes de banco, API, corrida e espera de bloqueio (ver `docs/estudo/pets.md`). |
| Pets: reserva com pet, cópia no agendamento e leitura pela agenda, fila e "Meus agendamentos" (PR 47: V9) | Pronto no backend (ver `docs/estudo/pets-reserva-e-leitura.md`). Telas no PR 48 (`docs/estudo/pets-telas.md`) e `petId` obrigatório na API e no banco no PR 49 (`docs/estudo/pets-obrigatorio.md`). |

## Limites honestos
As limitações vigentes estão no [README](../../README.md#limitações-conhecidas) (por exemplo: um atendimento por vez por estúdio, hospedagem gratuita que dorme, lembretes manuais, demonstração pública compartilhada). Este arquivo antes listava limites das primeiras etapas ("ainda não há API de agendamento", "ainda não há telas"); isso deixou de ser verdade e foi removido para não enganar quem lê.

O projeto foi desenvolvido com assistência substancial de IA (ver README).
