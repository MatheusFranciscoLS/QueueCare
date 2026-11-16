# Estudo: endpoint da agenda da semana

## O que é
`GET /api/agenda/week?start=AAAA-MM-DD` devolve os agendamentos dos **7 dias civis** a partir de `start`, no fuso do prestador. Mesma resposta e mesmas regras de `GET /api/agenda?date=` (sem cancelados, só id e nome do cliente, prestador sempre resolvido no servidor pelo usuário autenticado; `providerId` na URL é ignorado).

## Por que assim
- **Intervalo meio-aberto [início do 1º dia, início do 8º dia) no fuso IANA**: correto em semanas com horário de verão (uma semana pode ter 167 ou 169 horas). Somar 168 horas erraria.
- **Instantes em UTC na resposta**: quem agrupa por dia é a tela, no fuso do negócio (já existe `timeIn`/`todayIn`).
- **Sem endpoint novo de regras:** reaproveita `findDayAgenda`; o `day` agora também usa o mesmo método interno (`range`).

## Testes (9, PostgreSQL real)
Limites exatos (primeiro e último instante, 8º dia fora, semana anterior termina onde esta começa), virada de mês e de ano, horário de verão (America/New_York em 14/03/2027), isolamento entre prestadores e atendente vinculado, `providerId` ignorado, cliente e atendente sem vínculo recusados, prestador sem negócio, data inválida e ausente, sem vazamento de dados pessoais. Mutação verificada: trocar 7 por 8 dias derruba 2 testes.

## Perguntas de entrevista
1. Por que não somar 7×24 horas ao início da semana, e o que acontece no dia em que o relógio muda?
2. Por que a API devolve instantes UTC e deixa a tela agrupar por dia?
3. Como o isolamento entre prestadores é garantido sem receber o prestador como parâmetro?
