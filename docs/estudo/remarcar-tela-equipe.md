# Estudo: remarcar, tela da equipe (PR 53)

## O que é
Na **agenda do dia** a equipe (dono e atendente) ganha **Remarcar** (em agendamentos agendados) e **Histórico** (com o nome de quem agiu). É a mesma caixa de remarcar do cliente, reaproveitada. Último PR do ciclo de Remarcar (ADR 0017).

## Decisões que valem entrevista
- **Uma caixa só, dois papéis.** `RescheduleBox` virou genérica: recebe o alvo (id, prestador, serviço, horário, fuso), a função que chama a API e o texto de apoio. O cliente explica "até 3 remarcações, dentro do prazo"; a equipe, "sem prazo e sem limite; o histórico registra quem remarcou". A regra mora na API; a tela só a apresenta.
- **A equipe precisava do id do prestador.** Os horários livres são `GET /api/providers/{id}/slots` e a equipe não conhecia o id do prestador em que opera. O local de trabalho (`/api/agenda/workplace`) passou a devolver `providerId` (o mesmo id público do catálogo; nada de novo é exposto). Mudança pequena de backend neste PR, com a asserção no teste existente e o contrato OpenAPI regenerado.
- **A linha que muda de dia sai da lista na hora.** Se o novo horário cai em outro dia, a linha some imediatamente (mesmo que a lista refeita ainda a mostre); se cai no mesmo dia, a lista refeita a reposiciona. O foco vai para o aviso do resultado, que continua montado (a linha pode ter saído).
- **Histórico com nomes só para a equipe.** O mesmo componente do cliente, com `showNames`: a equipe lê "Remarcado por Beatriz da Equipe"; o cliente continua lendo "pelo estúdio". A API só envia o nome à equipe; a tela também só o mostra quando pedido.
- **Agendamento que já começou** (check-in feito por outra pessoa) fecha a caixa, avisa e refaz a lista; horário tomado mantém a caixa e refaz os horários.

## Testes
- `reschedule-staff.test.tsx` (6): botão só nos agendados; horários do prestador ignorando o próprio agendamento e o texto "sem prazo e sem limite"; remarcar no mesmo dia (envio, aviso, lista refeita e foco no aviso); para outro dia (a linha sai na hora); horário tomado e agendamento que começou; dois envios no mesmo instante; histórico com nomes.
- Backend: o teste do local de trabalho confere o `providerId`. Suítes: front 325 testes; backend 581. Tipos, lint e build verdes. Um teste antigo foi relaxado (a linha de um atendimento concluído continua sem botões de ação, mas agora tem "Histórico").
- **Mutação verificada** (6, todas derrubadas): linha de outro dia continuando na lista; foco perdido depois de remarcar; prestador errado nos horários; equipe sem nomes no histórico; agendamento que começou sem refazer a lista; cliente vendo nomes de funcionários.

## Limitações e o que falta
- **Não verifiquei em navegador real** (só jsdom), como nos PRs anteriores.
- No mesmo dia, até a lista refeita chegar, a linha mostra o horário antigo (a lista de agenda não guarda o agendamento movido localmente como a do cliente guarda).
- A visão semanal do prestador ainda não tem tela.

## Perguntas de entrevista
1. Por que generalizar a caixa de remarcar em vez de copiá-la para a equipe? O que muda entre os dois papéis e onde essa diferença é aplicada?
2. Por que o local de trabalho passou a devolver o id do prestador, e por que isso não expõe nada de novo?
3. Para onde deve ir o foco quando a linha que se acabou de remarcar pode sair da lista?
