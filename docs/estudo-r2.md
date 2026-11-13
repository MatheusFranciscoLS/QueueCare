# Roteiro de estudo da R2 (sobreposição de horários)

Feito para o Matheus implementar e explicar. A IA explica, tira dúvidas e revisa; **a lógica e os testes são dele**. Combinado com o ChatGPT (revisor independente).

## 1. Desenhe numa linha do tempo
Um agendamento é um trecho da linha do tempo. `[` quer dizer "o início conta"; `)` quer dizer "o fim não conta".

```
horas:      09:00  10:00  11:00  12:00
existente:         [=====)               [10:00, 11:00)
A (10:30):             [==)              [10:30, 11:00)   -> encosta dentro: SOBREPÕE
B (11:00):                   [=====)     [11:00, 12:00)   -> começa onde o outro termina: NÃO sobrepõe
C (09:45):      [===)                    [09:45, 10:15)   -> invade os 15 min iniciais: SOBREPÕE
```

Pergunta para você: **quando dois intervalos NÃO se sobrepõem?** (Pense em duas situações: um inteiro antes do outro, ou um inteiro depois.) A resposta vira o `overlaps`.

## 2. Passo a passo (TDD: teste primeiro)
1. Faça o checkout da branch `feat/r2-time-range` e rode o teste existente (`TimeRangeTest`). Ele **falha de propósito** (vermelho), porque `overlaps` ainda não foi implementado.
2. Em `TimeRange.overlaps`, escreva a lógica até o teste passar (verde).
3. Escreva os testes dos cenários B a G e I a K de `docs/cenarios-r2.md`, **um de cada vez**, vendo cada um passar ou falhar.
4. Invente 3 cenários seus e escreva os testes.
5. Decida o que fazer com intervalos inválidos (nulos, fim <= início) no construtor e teste (cenário J).
6. Abra o PR (o CI precisa ficar verde) e peça a revisão.

Como rodar os testes (na pasta `backend`, com JDK 21):
```bash
./mvnw test             # PowerShell: .\mvnw.cmd test
```

## 3. Explique sem consultar a IA
- Por que `[)` permite um agendamento começar exatamente quando o outro termina?
- Por que o `overlaps()` sozinho **não** impede duas reservas ao mesmo tempo? (Dois clientes verificam ao mesmo tempo que o horário está livre; os dois passam; os dois reservam.)
- Como o PostgreSQL resolve isso? (ADR 0002.)

## 4. Depois da R2 pura
Entidades e migrations Flyway (você escreve, com a constraint do ADR 0002) -> R1 (disponibilidade) -> R5 (fuso e antecedência) -> autenticação (ADR 0003, comparar alternativas) -> endpoints, T3 e HTTP 409.
