# Estudo: R5, fuso e antecedência (PR 8)

## Em linguagem simples
A R5 tem duas partes, ambas funções puras (sem banco e sem API):

**1. Passado e antecedência mínima (`BookingTimeRules`).** Não se pode agendar para um horário que já passou, e o prestador pode exigir uma antecedência (ex.: 1 hora). O resultado é um destes: `OK`, `IN_THE_PAST` (já passou) ou `TOO_SOON` (ainda não passou, mas está muito perto). Estar exatamente no limite da antecedência é permitido. O "agora" entra como parâmetro, para os testes serem sempre iguais.

**2. Horário local digitado -> instante (`LocalTimeResolver`).** Quando alguém escolhe "01/11 às 01:30" no fuso do prestador, o sistema precisa descobrir o instante em UTC. No horário de verão isso tem três respostas possíveis:
- **Um instante** (normal).
- **Nenhum** (horário inexistente): quando o relógio *avança*, uma hora some. Ex.: em Nova York, em 08/03/2026, não existe 02:30; o próximo horário válido é 03:00.
- **Dois** (horário ambíguo): quando o relógio *volta*, uma hora acontece duas vezes. Ex.: em 01/11/2026 em Nova York, 01:30 acontece às 05:30 UTC e de novo às 06:30 UTC.

```
Primavera (relógio avança):   01:59 | (02:00 a 02:59 NÃO existem) | 03:00
Outono (relógio volta):       01:00 a 01:59 (1ª vez) | 01:00 a 01:59 (2ª vez) | 02:00
```

**Política: o sistema não adivinha.** Ele devolve "inexistente" ou "ambíguo" e quem chama decide (por exemplo, a API responderá 400 pedindo outro horário ou que a pessoa escolha qual das duas ocorrências). Escolher sozinho poderia agendar um horário diferente do que o cliente quis.

Detalhes importantes:
- Usa as regras do fuso do Java (`ZoneRules`), então vale para transições de qualquer duração (Lord Howe, na Austrália, tem 30 minutos) e para datas históricas (São Paulo tinha horário de verão até 2019).
- São Paulo hoje não tem horário de verão; os testes usam Nova York e Lord Howe para exercitar a regra.

## Onde está o código
- `backend/src/main/java/com/queuecare/scheduling/domain/`: `BookingTimeRules`, `BookingTimeCheck`, `LocalTimeResolver`, `ResolvedLocalTime`
- Testes: `BookingTimeRulesTest` (11) e `LocalTimeResolverTest` (19)

## 3 perguntas de entrevista (responda com as suas palavras)
1. O que acontece com um horário local que "não existe" no horário de verão, e por que o sistema não escolhe um horário parecido sozinho?
   *Pista: 02:30 em Nova York em 08/03/2026.*
2. Por que o "agora" é passado como parâmetro em vez de a classe consultar o relógio do computador?
   *Pista: testes determinísticos e o fuso do servidor.*
3. Qual a diferença entre converter um instante conhecido para o horário local (R1) e converter um horário local digitado para instante (R5)?
   *Pista: um tem sempre uma resposta; o outro pode ter zero ou duas.*

## Limitações e o que ainda falta
- Ainda não há a ligação com R1/R2, com o relógio de serviço (`Clock`), com a API (que traduz os resultados em HTTP 400) nem com o banco (`timestamptz` em UTC).
- Sem política automática de escolha para horário ambíguo; a decisão da interface ainda será definida.
