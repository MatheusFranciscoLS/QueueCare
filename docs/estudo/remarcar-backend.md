# Estudo: remarcar, backend (PR 51)

## O que é
Remarcar **move o mesmo agendamento** para outro horário (mesma linha, mesmo id, mesma duração, mesmo pet). `POST /api/appointments/{id}/reschedule` com o novo horário local. Também entram o histórico consultável (`GET /api/appointments/{id}/events`) e o parâmetro `excludeAppointmentId` nos horários livres.

## Decisões que valem entrevista
- **Mover, não cancelar e criar.** Uma linha só: o id, o pet e a cópia dos dados continuam; não há instante em que a vaga some entre dois passos; o histórico fica ligado.
- **As regras do horário vivem num lugar só.** `SlotRules` (fuso, passado, antecedência, expediente, sobreposição) foi extraída do `BookingService` e é usada por reservar e por remarcar. Os testes da reserva rodaram iguais depois da extração.
- **Cliente e equipe têm papéis diferentes.** O cliente remarca só se o horário ATUAL ainda está dentro do prazo (remarcar em cima da hora seria cancelar disfarçado) e até 3 vezes. A equipe remarca sem prazo e sem limite: ator autenticado e auditado, então a salvaguarda é a **rastreabilidade** (cada remarcação grava quem, quando, de qual horário para qual), não uma cota. Decisão confirmada pelo Matheus e pelo ChatGPT (ADR 0017).
- **O histórico precisa ser consultável.** Sem isso a auditoria seria só um depósito. O cliente vê "cliente" ou "equipe" (nunca o nome de um funcionário); a equipe vê os nomes. Ordem: instante e, em empate, id.
- **A V6 permitia um evento por tipo.** Remarcar repete. A restrição virou **índice único parcial** (todos os tipos menos RESCHEDULED), então chegada e conclusão continuam únicas (a fila depende disso). Migration testada sobre eventos antigos.
- **Concorrência pelas ferramentas que já existem:** `version` (remarcar x check-in x cancelar x atualização da cópia do pet), restrição de exclusão para horário disputado (SLOT_TAKEN depois do rollback) e espera limitada de bloqueio (RESOURCE_BUSY). O limite de 3 é lido dentro da transação protegida pela versão: duas remarcações simultâneas não passam dele.
- **Horários livres ignoram o próprio agendamento**, mas só para quem pode: se o agendamento não for do chamador o parâmetro é ignorado, sem erro e sem pista de que ele existe.

## Testes
- `RescheduleApiIntegrationTest` (22, PostgreSQL real): caminho feliz com evento; dono e atendente; deslocar sobrepondo o próprio horário; limite de 3 do cliente (4ª = 422) x equipe aceita a 5ª, 10ª e 20ª; prazo; situação; regras do horário; horário ocupado; autorização (mesma 404, 403, 401); pet arquivado e agendamento antigo sem pet; corridas **determinísticas** (uma transação aberta segura a linha): reserva em andamento no horário novo, check-in que vence, 3ª remarcação em andamento; 6 remarcações simultâneas; atomicidade com gatilho que quebra o evento; entidade obsoleta; histórico (nomes, 404, ordem); horários livres.
- `RescheduleEventConstraintsTest` (4): índice parcial, CHECKs e migration sobre eventos antigos. `RescheduleLockTimeoutIntegrationTest` (1): 409 rápido e solta depois.
- **Mutação verificada** (17): status, prazo, limite fora por um, equipe com prazo, agendamento conflitando consigo mesmo, equipe de outro prestador, sem evento, nome da equipe vazando, slots com agendamento alheio, falha otimista virando erro genérico, NO_CHANGE depois das regras, histórico fora de ordem, índice não parcial, CHECK sem horários diferentes, CHECK dos outros tipos e espera limitada removida (esta "morre" travando o teste, pois a chamada espera para sempre; a mutação foi interrompida à mão).

## Limitações e o que falta
- Remarcar não troca prestador, serviço nem pet (cancelar e agendar de novo).
- A duração dos horários livres é a do serviço atual; a validação da remarcação usa a do agendamento. Se o serviço foi editado depois, a tela pode oferecer um horário que a validação recusa (OUTSIDE_AVAILABILITY).
- Telas de remarcar (cliente e equipe) são os próximos PRs.

## Perguntas de entrevista
1. Por que mover a mesma linha em vez de cancelar e criar outra? O que ficaria pior no histórico e na concorrência?
2. Por que a equipe pode remarcar sem limite e o cliente não? Qual é a salvaguarda de cada um?
3. Como a V11 mantém "um evento de chegada por agendamento" e ainda permite várias remarcações?
