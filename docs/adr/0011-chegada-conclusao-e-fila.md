# ADR 0011: chegada (check-in), conclusão e fila do dia

- **Status:** Accepted
- **Data:** 2026-11-14
- **Quem decidiu:** o Matheus delegou (modo autônomo noturno); desenho de Claude, revisão do ChatGPT (esforço MÉDIO, escopo simples: sem tempo real, sem reordenação manual, uma fila por prestador).

## Contexto
O banco já prevê as situações `CHECKED_IN` e `COMPLETED` e os eventos correspondentes, mas nenhuma rota os usava. Falta a equipe registrar que a pessoa chegou, concluir o atendimento e ver quem espera.

## Decisão
1. **Duas ações, só para a EQUIPE** (PROVIDER dono e ATTENDANT vinculado): `POST /api/appointments/{id}/check-in` e `POST /api/appointments/{id}/complete`. Regra na rota e na anotação; CUSTOMER e ADMIN recebem 403. O prestador vem SEMPRE de `StaffAccess` (banco a cada chamada), nunca da requisição.
2. **Isolamento:** agendamento de outro prestador ou inexistente responde 404 igual; atendente sem vínculo, ou cujo dono foi desativado, recebe 403 antes de qualquer consulta ao agendamento.
3. **Máquina de estados, só para frente:** `SCHEDULED` para `CHECKED_IN` para `COMPLETED`. Cancelado: 409 `ALREADY_CANCELLED`; qualquer outra situação fora da ordem: 409 `INVALID_STATE`. Quem já chegou não se cancela mais (a regra de cancelamento já respondia `NOT_CANCELLABLE`).
4. **Check-in só no DIA do agendamento**, no fuso do prestador: outro dia responde 422 `WRONG_DAY`. Impede marcar chegada de agendamentos futuros ou esquecidos de ontem.
5. **Histórico:** cada transição grava o evento (`CHECKED_IN`, `COMPLETED`) com quem fez, na mesma transação da mudança de situação.
6. **Concorrência:** duas requisições ao mesmo agendamento: uma vence, a outra recebe 409 (`INVALID_STATE` se leu depois, `CONCURRENT_UPDATE` se colidiu), por bloqueio otimista (`version`). Testes disparam duas chegadas simultâneas, chegada x cancelamento e chegada x conclusão (6 rodadas cada), conferindo situação final e histórico.
7. **Um evento de cada tipo por agendamento, garantido pelo banco** (migration V6: `UNIQUE (appointment_id, event_type)`): as transições só andam para frente, e a fila lê o evento de chegada, então uma duplicata faria a mesma pessoa aparecer duas vezes. Um teste confirma que o banco recusa o segundo evento.
8. **Fila:** `GET /api/agenda/queue` devolve os agendamentos de HOJE (dia do prestador) em `CHECKED_IN`, por ordem de CHEGADA (instante do evento, depois id), com posição 1, 2, 3... Concluído sai da fila. Dados do cliente: só id e nome. Não existe reordenação manual nem prioridade.

## Alternativas descartadas
- **Coluna `checked_in_at` no agendamento:** duplicaria o instante que o histórico já guarda; a fila lê do evento.
- **Fila de todos os dias:** um agendamento esquecido de ontem entupiria a fila de hoje; ele continua na agenda do dia dele e pode ser concluído lá.
- **Tempo real (websocket) e reordenação manual:** fora do escopo (roadmap).

## Consequências e limitações conhecidas
- Quem chegou e nunca foi concluído fica `CHECKED_IN` na agenda do dia; não há expiração automática nem "faltou" (no-show).
- A posição é a ordem da lista no momento da consulta; a tela precisa atualizar para refletir mudanças.
