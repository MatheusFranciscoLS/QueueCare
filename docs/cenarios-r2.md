# Cenários da regra R2 (sem reserva dupla e sem sobreposição)

Documento de estudo. Serve de base para os testes da R2 (incluindo o T3, de concorrência). O Matheus deve conseguir explicar cada linha com as próprias palavras antes de implementar.

## A ideia em uma frase
Um agendamento ocupa um intervalo de tempo que **começa no início e termina antes do fim** (escreve-se `[início, fim)`: o início conta, o fim não). Dois agendamentos ativos do **mesmo prestador** não podem ter nenhum instante em comum.

Dois intervalos `[a, b)` e `[c, d)` se sobrepõem quando `a < d` **e** `c < b`.

## Tabela de cenários
Base: agendamento existente, 60 min às 10:00, ou seja, `[10:00, 11:00)`, do prestador P1, ativo.

| # | Novo agendamento | Intervalo novo | Pode? | Por quê |
|---|---|---|---|---|
| A | 30 min às 10:30, P1 | `[10:30, 11:00)` | **Não** (409) | Está dentro do horário já ocupado. Começa em hora diferente, por isso um índice único simples em `(prestador, início)` não pega. |
| B | 60 min às 11:00, P1 | `[11:00, 12:00)` | **Sim** | Começa exatamente quando o outro termina. Como o fim não conta, não há instante em comum. |
| C | 30 min às 09:45, P1 | `[09:45, 10:15)` | **Não** (409) | Os 15 min entre 10:00 e 10:15 coincidem. Também começa em hora diferente. |
| D | 60 min às 10:00, P1, mas o existente foi **cancelado** | `[10:00, 11:00)` | **Sim** | Cancelado não ocupa a agenda. |
| E | 60 min às 10:00, **P2** | `[10:00, 11:00)` | **Sim** | Outro prestador tem outra agenda. |
| F | 60 min às 10:00, P1, igual ao existente | `[10:00, 11:00)` | **Não** (409) | Reserva dupla clássica. |
| G | 120 min às 09:30, P1 (cobre o existente por inteiro) | `[09:30, 11:30)` | **Não** (409) | O novo envolve o existente. Quem só olha o início do existente não vê. |
| H | Duas requisições iguais ao mesmo tempo para um horário livre | mesmo intervalo | **Uma vence, a outra recebe 409** | É o teste T3. Só o banco consegue garantir isso, porque duas requisições podem passar pela checagem da aplicação ao mesmo tempo. |

## Para estudar
1. Por que a checagem só na aplicação não basta (cenário H)?
2. Por que o fim do intervalo não conta (cenário B)?
3. O que muda quando o agendamento é cancelado (cenário D)?
