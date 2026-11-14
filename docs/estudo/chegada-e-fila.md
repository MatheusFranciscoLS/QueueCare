# Estudo: chegada, conclusão e fila (PR 26, backend do M4)

## Em linguagem simples
Quando a pessoa chega, a equipe marca "chegou"; ao terminar, marca "concluído". Quem chegou e espera aparece numa fila, na ordem em que chegou.

| Chamada | Quem pode | Resultado |
|---|---|---|
| `POST /api/appointments/{id}/check-in` | dono ou atendente do prestador | agendado para chegou (200) |
| `POST /api/appointments/{id}/complete` | dono ou atendente do prestador | chegou para concluído (200) |
| `GET /api/agenda/queue` | dono ou atendente | fila de hoje, por ordem de chegada |

## Regras
- Só avança: agendado, chegou, concluído. Cancelado não chega (`ALREADY_CANCELLED`); fora de ordem é `INVALID_STATE` (409).
- Check-in só no dia do agendamento, no fuso do prestador (`WRONG_DAY`, 422).
- Agendamento de outro prestador: 404 igual ao inexistente. Cliente e atendente sem vínculo: 403.
- Cada passo grava o evento no histórico com quem fez.
- Duas pessoas marcando a mesma chegada: uma vence, a outra recebe 409.

## Como ler o código
1. `AttendanceService` (as duas transições e as regras), `Appointment.checkIn/complete`.
2. `AgendaService.queue` e a consulta `findQueue` (ordem pelo instante do evento).
3. `AttendanceApiIntegrationTest` (10 testes).

## O que os testes provam (HTTP real + PostgreSQL real)
- Fluxo completo com evento e autor; fila muda a cada passo.
- Matriz hostil: prestador B e atendente B não operam o agendamento do A (404), cliente e atendente solto recebem 403, sem token 401; nada muda no banco.
- Atendente desvinculado ou com o dono desativado perde o acesso na hora.
- Máquina de estados completa, cancelado, e "em atendimento não cancela".
- Check-in de ontem e de amanhã recusado; hoje funciona.
- Fila: ordem de CHEGADA (não de horário nem de id), só hoje, só chegou, só o prestador de quem pergunta, `providerId` na URL ignorado, sem e-mail do cliente.
- Duas chegadas simultâneas, 3 rodadas: um 200, um 409, um único evento.
- Mutações verificadas: sem checar o dia, sem filtrar o prestador e com a fila por horário, cada uma derruba o seu teste.

## Três perguntas de entrevista
1. **Como duas pessoas marcando a mesma chegada não geram dois eventos?** Bloqueio otimista (`version`) mais conferência da situação: a segunda leitura vê CHEGOU e recusa, ou a gravação colide e vira 409.
2. **Por que a fila lê o evento e não uma coluna?** O histórico já guarda o instante da chegada; uma coluna a mais seria um segundo lugar para ficar fora de sincronia.
3. **Por que o check-in só vale no dia?** Para não marcar chegada de agendamentos futuros nem reviver os de dias passados; o "dia" é o do prestador, no fuso dele.

## Limitações
- Sem "faltou" (no-show) nem expiração de quem ficou em CHEGOU; sem tempo real; sem reordenação manual. As telas vêm no próximo PR.
