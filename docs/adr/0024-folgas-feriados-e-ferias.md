# ADR 0024: folgas, feriados e férias

- **Status:** Accepted (implementado em 21/11/2026, primeiro item do plano de prontidão para uso real)
- **Data:** 21/11/2026
- **Esforço:** MÉDIO (tabela nova, um serviço, três endpoints, uma seção de tela e duas mensagens)

## Contexto: a análise crítica
O expediente é **semanal e recorrente**: "toda segunda, das 8h às 18h". Ele não sabe do 25 de dezembro, da semana de férias nem do dia em que o profissional vai ao médico. Na primeira vez que isso acontecer num estúdio real, a consequência é concreta: o tutor agenda num dia em que ninguém vai atender, e o estúdio descobre pela reclamação do tutor. A única saída hoje seria apagar o expediente daquele dia, o que destrói a configuração semanal e depende de lembrar de refazê-la.

Duas ideias foram descartadas:
- **Bloquear horários dentro do dia.** Cobre também "saio mais cedo na quinta", mas multiplica a tela e as regras (intervalos parciais, sobreposição com agendamentos, duração dos serviços). Dias inteiros resolvem feriado, folga e férias, que são a maioria dos casos. Bloqueio parcial fica registrado como evolução, sem mudar o modelo de dados de forma incompatível.
- **Cancelar sozinho o que já estava marcado.** Seria decidir pelo estúdio e pelo tutor. Fechar um período nunca mexe em agendamento existente.

## Decisão
1. **Um período fechado é um intervalo de dias inteiros**, de `starts_on` a `ends_on` (inclusive), na data local do **fuso do estúdio**, com um motivo opcional de até 80 caracteres. Tabela `time_off` (V23), com `CHECK (starts_on <= ends_on)`.
2. **A reserva e a remarcação recusam o período** com o motivo `CLOSED_DAY` (HTTP 422), na mesma classe que concentra as regras de horário (`SlotRules`, ADR 0017), para as duas operações nunca divergirem. A checagem vem **antes** do expediente: o dia fechado vence qualquer janela semanal.
3. **A consulta de horários livres vem vazia** num dia fechado, e a lista de espera recusa o dia (ninguém vagaria nele).
4. **Fechar não cancela nada.** A resposta traz `affectedAppointments`, quantos agendamentos ativos já existiam no período, e a tela leva à Agenda do primeiro dia para a equipe remarcar ou cancelar um a um. A Agenda do dia mostra "Estúdio fechado neste dia" com o motivo, mas continua listando o que já existia.
5. **Validações do servidor:** o último dia não vem antes do primeiro; o período não terminou no passado; começa em até 730 dias; dura até 366 dias; **não se sobrepõe** a outro período do mesmo estúdio; até 200 períodos por estúdio (a tela só lista o que ainda vale, então o dono remove os antigos de vez em quando).
6. **Quem enxerga o quê:** só o `PROVIDER` cria e remove, sempre no PRÓPRIO negócio (o id vem do token; remover folga alheia ou inexistente responde 404 igual). O tutor lê os períodos do estúdio em `GET /api/catalog/providers/{id}/closures` para o Agendar explicar o dia fechado em vez de oferecer lista de espera; a equipe os recebe junto de `GET /api/agenda/workplace`.

## Por que sem bloqueio de concorrência
O expediente semanal usa o único bloqueio pessimista da aplicação (ADR do expediente) porque substitui o conjunto inteiro. Aqui cada período é uma linha independente; dois cliques simultâneos podem, no pior caso, gravar dois períodos que se sobrepõem, e o efeito é o mesmo (o dia fica fechado). Não vale um segundo bloqueio e uma ordem de bloqueios a manter por isso.

## Consequências
- O estúdio fecha o 25/12 em dois toques e o tutor nunca consegue agendar nele, nem por remarcação.
- O fechamento é visível dos dois lados: o tutor lê "O estúdio está fechado neste dia (motivo)" e a equipe vê o aviso na Agenda.
- Não há bloqueio parcial do dia nem recorrência anual: feriado fixo se cria uma vez por ano. É o preço de manter o modelo simples; a evolução natural é um bloqueio com horário, sem trocar a tabela.
- Testes: 8 de integração no backend (dia fechado nos dois extremos do período, reserva, remarcação, lista de espera, não cancelar o existente, validações, isolamento entre estúdios, papéis) e 11 no frontend.
