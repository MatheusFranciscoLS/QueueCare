# Estudo: API de agendar e cancelar (PR 12)

## Em linguagem simples
Até aqui o agendamento existia por dentro (serviço + banco) e o login existia à parte. Este PR **liga os dois pela API HTTP**.

| Chamada | Quem pode | Resultado |
|---|---|---|
| `POST /api/appointments` | CLIENTE | agenda (201). Corpo: prestador, serviço e **início no horário local do prestador** |
| `GET /api/appointments/mine` | CLIENTE | lista **só os seus** agendamentos |
| `POST /api/appointments/{id}/cancel` | CLIENTE dono ou PRESTADOR dono | cancela (200) |

**Regra de ouro (R4):** quem está agendando ou cancelando vem **sempre do token**. O corpo da requisição não tem (e se tiver, é ignorado) campo de cliente. Assim ninguém age "em nome de outro" mandando um id diferente.

## Respostas HTTP
- **409** horário ocupado (`SLOT_TAKEN`), inclusive quando duas pessoas pedem o mesmo horário ao mesmo tempo: uma recebe 201 e a outra 409, nunca 500. Também 409 ao cancelar de novo (`ALREADY_CANCELLED`), cancelar algo já concluído (`NOT_CANCELLABLE`) ou quando dois cancelamentos chegam juntos.
- **422** regra de negócio violada: fora do expediente, no passado, muito cedo, horário local inexistente/ambíguo (`OUTSIDE_AVAILABILITY`, `IN_THE_PAST`, `TOO_SOON`...) e cancelamento fora do prazo (`TOO_LATE`).
- **404** prestador/serviço inexistente, ou agendamento que **não existe ou não é seu** (mesma resposta, para não revelar que o agendamento de outra pessoa existe).
- **403** perfil sem permissão (ex.: prestador tentando agendar; atendente tentando cancelar).
- **401** sem token ou token inválido. **400** corpo inválido.

## Cancelamento (R3)
- O **cliente** cancela só o próprio e **dentro do prazo** do prestador (ex.: até 2 h antes). Exatamente no limite ainda pode.
- O **prestador** cancela os agendamentos do **próprio negócio**, sem prazo.
- **Atendente:** ainda não está ligado a nenhum prestador no banco, então recebe 403 até existir esse vínculo.
- Cancelar libera o horário na hora (a trava do banco ignora cancelados) e grava o evento `CANCELLED` com quem cancelou.
- Dois cancelamentos simultâneos do mesmo agendamento: um vence (200), o outro recebe 409 (bloqueio otimista por `version`).

## O que os testes provam (HTTP real + PostgreSQL real)
- **API (16 testes):** o cliente vem do token (mesmo com `customerId` no corpo), UTC e duração corretos, 401/403, 409, 422, 404, 400, lista só do dono, cancelar (próprio, de outro, duas vezes, fora do prazo, prestador dono e de outro negócio, atendente, concluído), **duas reservas simultâneas pela API** (5 rodadas) e **dois cancelamentos simultâneos**.
- **R3 pura (7 testes):** `CancellationPolicy` (limite exato, 1 ns dentro do prazo, já iniciado, prazo zero, prazo enorme).

## Onde está
- `backend/src/main/java/com/queuecare/scheduling/web/`: `AppointmentController`, `SchedulingExceptionHandler`
- `backend/src/main/java/com/queuecare/scheduling/application/`: `CancellationService`, `AppointmentExceptions`
- `backend/src/main/java/com/queuecare/scheduling/domain/CancellationPolicy.java`

## 3 perguntas de entrevista (responda com as suas palavras)
1. Por que o corpo do pedido não traz o id do cliente, e o que aconteceria se trouxesse?
   *Pista: autorização por dono (IDOR) e o token como única fonte de identidade.*
2. Por que cancelar o agendamento de outra pessoa responde 404 e não 403?
   *Pista: não revelar que o recurso existe.*
3. O que garante que, com dois cliques simultâneos no mesmo horário, só um agendamento é criado e o outro recebe 409 em vez de erro 500?
   *Pista: trava do banco (ADR 0002) e tradução do erro depois do rollback.*

## Limitações e o que ainda falta
- Atendente sem vínculo com prestador (precisa de uma coluna/tabela no modelo); sem painel do dia, check-in e fila (M4).
- Sem consulta de horários livres para a tela (disponibilidade como lista); sem tela.
- `/mine` é paginado (`page` a partir de 0, `size` de 1 a 50, padrão 20, mais recentes primeiro); fora desses limites responde 400.
- O limite de tentativas é em memória e por instância (ver ADR 0003).
