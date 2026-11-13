# Estudo: vínculo atendente-prestador e agenda do dia (PR 15)

## Em linguagem simples
O **atendente** trabalha para **um** prestador. O prestador (dono) o **vincula** à equipe e, a partir daí, o atendente pode **ver a agenda do dia e cancelar** agendamentos **desse** prestador. A pergunta de segurança é uma só: *"este usuário pode mexer neste agendamento?"* A resposta vem sempre do **servidor**, olhando o **banco**, nunca de algo que o cliente envia.

```
Atendente A1 --(vinculado)--> Prestador A      Atendente B1 --(vinculado)--> Prestador B
A1 vê a agenda de A e cancela agendamentos de A.   A1 NÃO vê nem cancela nada de B (responde 404 "não existe").
```

| Chamada | Quem | O que faz |
|---|---|---|
| `POST /api/provider/staff` (e-mail) | PRESTADOR dono | vincula um atendente ao PRÓPRIO negócio |
| `GET /api/provider/staff` | PRESTADOR dono | lista a equipe |
| `DELETE /api/provider/staff/{userId}` | PRESTADOR dono | desvincula |
| `GET /api/agenda?date=` | PRESTADOR dono e ATENDENTE vinculado | agenda do dia do prestador em que opera |
| `POST /api/appointments/{id}/cancel` | cliente dono, prestador dono, atendente vinculado | cancela (equipe sem prazo; cliente dentro do prazo) |

## As regras de segurança (e por quê)
- **O prestador nunca vem do cliente.** Não existe parâmetro de prestador na agenda nem no cancelamento da equipe; `providerId` na URL é ignorado. Um único componente (`StaffAccess`) responde "em qual prestador este usuário opera?".
- **A resposta vem do banco a cada chamada** (sem cache e sem guardar o prestador no token). Por isso **desvincular, desativar ou trocar de prestador vale na hora**, mesmo com o token antigo.
- **O banco também garante**: só ATENDENTE pode ser vinculado; um atendente só em um prestador; quem está vinculado não muda de perfil nem é apagado.
- **Sem pistas de existência**: agendamento de outro prestador responde igual a "não existe" (404); e-mail inexistente, de quem não é atendente e de usuário inativo respondem **exatamente igual** (404). Vincular alguém já vinculado dá 409, sem dizer a quem.
- **A agenda mostra só o necessário**: do cliente, id e nome (nada de e-mail).
- A **data** da agenda é o dia no fuso do prestador: de 00:00 local até 00:00 do dia seguinte, correto também em dias de horário de verão (dia de 23 h e de 25 h).

## O que os testes provam (42 novos; 260 no total)
- **Banco (6):** só ATTENDANT vincula; 1 prestador por atendente; mudar perfil ou apagar vinculado é recusado; prestador com equipe não é apagado.
- **StaffAccess (8):** dono, atendente vinculado e sem vínculo, cliente, inativo; **mudanças no banco valem na chamada seguinte**.
- **Matriz adversária por HTTP (22 + os demais):** cada um dos 7 atores tenta ver e cancelar o que é do outro prestador; depois de TODAS as tentativas nada mudou; `providerId` ignorado; desvincular tira o acesso com o mesmo token; trocar de prestador muda o contexto; atendente desativado perde o acesso; vínculo repetido e e-mails sem enumeração; só o prestador gerencia a equipe; **concorrência** (dois prestadores vinculando o mesmo atendente; vincular × desvincular; dois cancelamentos); agenda sem cancelados, ordenada, sem outros dias e **em dias de horário de verão**.
- **Prova de que a matriz presta:** quebrando de propósito a checagem de prestador no cancelamento, o teste falha (atendente cancelava agendamento de outro prestador); o mesmo com a resolução do `StaffAccess`.

## Onde está
`backend/src/main/java/com/queuecare/scheduling/`: `application/StaffAccess`, `StaffService`, `AgendaService`, `CancellationService` (usa `StaffAccess`), `web/StaffController`, `AgendaController`; migration `V2__vinculo_atendente.sql`; ADR 0004.

## 3 perguntas de entrevista (responda com as suas palavras)
1. Por que o prestador da agenda nunca é um parâmetro da requisição, e por que não fica guardado no token?
   *Pista: IDOR e revogação imediata.*
2. Por que um agendamento de outro prestador responde 404 e não 403, e por que e-mails inexistentes respondem igual aos de quem não é atendente?
   *Pista: não revelar existência (enumeração).*
3. Qual regra o próprio banco garante no vínculo (e por que não basta a aplicação conferir)?
   *Pista: FK composta (usuário, perfil) e UNIQUE por atendente.*

## Limitações e o que ainda falta
- Atendente em um único prestador; ainda **não existe criação de usuários PROVIDER/ATTENDANT** (próximo PR); sem auditoria detalhada; sem rate limit.
- O atendente ainda não agenda em nome de cliente; check-in e fila (M4) vêm depois.
