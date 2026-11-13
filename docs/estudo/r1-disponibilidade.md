# Estudo: R1, disponibilidade (PR 7)

## Em linguagem simples
**Problema:** só aceitar agendamento dentro do expediente do prestador, e só se o serviço **couber inteiro**. Exemplo: um serviço de 60 min às 17:30 com expediente até 18:00 não pode.

**Solução:** o prestador tem um **fuso** (ex.: `America/Sao_Paulo`) e **janelas semanais** em horário local (segunda 08:00-12:00 e 14:00-18:00). A classe `AvailabilityPolicy` pergunta: *"esse intervalo cabe inteiro numa janela?"* (`allows`).

```
segunda:  08:00 ------ 12:00   (almoço)   14:00 ------ 18:00
17:00-18:00  -> cabe (termina exatamente no fim)         PERMITIDO
17:30-18:30  -> passa do fim                              REJEITADO  (teste T2)
11:30-14:30  -> atravessa o almoço                        REJEITADO
```

Regras que a classe aplica:
- Os horários chegam como **instantes UTC** (como no banco) e são convertidos para o **fuso do prestador** para descobrir o dia da semana e a hora local. Um mesmo instante pode ser segunda em UTC e domingo em Nova York.
- Início e fim precisam cair no **mesmo dia local**. Terminar exatamente à meia-noite conta como dia seguinte (rejeitado).
- **Janelas coladas** (08:00-12:00 e 12:00-18:00) contam como um expediente contínuo; **qualquer lacuna** (até de 1 minuto) interrompe.
- Janelas sobrepostas no mesmo dia são uma configuração inválida (rejeitada no construtor). Lista vazia é válida e significa "não atende".
- No **início** do horário de verão (relógio avança), o intervalo é avaliado pelo relógio local resultante. Se o intervalo atravessa a **volta** do horário de verão (o mesmo horário local acontece duas vezes), o sistema **rejeita por segurança**, detectando a transição pelas regras do fuso (`ZoneRules`) em vez de comparar só os horários locais. Um erro desse tipo foi apontado na revisão do ChatGPT e virou teste.

## Onde está o código
- `backend/src/main/java/com/queuecare/scheduling/domain/AvailabilityWindow.java`
- `backend/src/main/java/com/queuecare/scheduling/domain/AvailabilityPolicy.java`
- `backend/src/test/java/com/queuecare/scheduling/domain/AvailabilityPolicyTest.java` (37 testes, incluindo o T2 e o horário de verão de `America/New_York`)

## 3 perguntas de entrevista (responda com as suas palavras)
1. Por que converter o instante para o fuso do prestador antes de decidir o dia da semana?
   *Pista: pense em segunda 01:00 UTC e em Nova York.*
2. Por que duas janelas coladas (12:00 e 12:00) são tratadas como contínuas, mas uma lacuna de 1 minuto não?
   *Pista: o que significa "expediente contínuo" para quem agenda.*
3. Por que a R1 não trata antecedência mínima, agendamento no passado ou horário local inexistente?
   *Pista: R5 e a diferença entre converter um instante para local e converter um horário local digitado em instante.*

## Limitações e o que ainda falta testar
- Expediente que vai até a meia-noite exata não é representável (`LocalTime` não tem 24:00): use 23:59.
- Sem exceções de data (feriados, folgas) por enquanto.
- Ainda não existe ligação com serviço (duração), banco, API ou R5 (antecedência, passado, horários locais inexistentes ou ambíguos).
