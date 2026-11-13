# Estudo: R2 pura, `TimeRange` (PR 6)

## Em linguagem simples
**Problema:** saber se dois horários marcados "batem" um no outro.
**Solução:** representar cada agendamento como um intervalo `[início, fim)` e comparar dois intervalos com uma conta: eles se sobrepõem quando **o início de cada um vem antes do fim do outro**.

```
horas:      09:00  10:00  11:00  12:00
existente:         [=====)               10:00 até 11:00
A (10:30):             [==)              SOBREPÕE
B (11:00):                   [=====)     NÃO sobrepõe (começa onde o outro termina)
C (09:45):      [===)                    SOBREPÕE
```

- `[` = o início conta; `)` = o fim não conta. Por isso um serviço pode começar exatamente quando o anterior termina.
- Um intervalo com duração zero ou negativa é **inválido** e é rejeitado ao ser criado (cenário J).
- A classe `TimeRange` usa instantes em UTC (`Instant`), como o `timestamptz` do banco.

**Impacto:** é a base da R2. Ela sozinha **não impede** reserva dupla sob requisições simultâneas; isso é papel do banco (ADR 0002) e será provado no teste T3.

## Onde está o código
- `backend/src/main/java/com/queuecare/scheduling/domain/TimeRange.java`
- `backend/src/test/java/com/queuecare/scheduling/domain/TimeRangeTest.java` (14 testes: cenários A, B, C, F, G, I, J, simetria, nulos)
- Cenários completos: `docs/cenarios-r2.md`

## 3 perguntas de entrevista (responda com as suas palavras)
1. Por que o fim do intervalo não conta, e o que isso permite no dia a dia da agenda?
   *Pista: compare os cenários B e A.*
2. Se a função `overlaps` já detecta conflitos, por que ainda precisamos de uma trava no banco?
   *Pista: pense em dois clientes verificando o mesmo horário no mesmo instante.*
3. Por que um índice único em (prestador, início) não resolve a sobreposição?
   *Pista: cenários A e C começam em horas diferentes do agendamento existente.*

## Limitações e o que ainda falta testar
- Não cobre os cenários D, E e K (cancelado e outro prestador): dependem de status e prestador, que virão com as entidades.
- O cenário H (concorrência) exige PostgreSQL real (teste T3).
- Ainda não há ligação com banco, API ou expediente (R1) e fuso (R5).
