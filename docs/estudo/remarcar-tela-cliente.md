# Estudo: remarcar, tela do cliente (PR 52)

## O que é
Em **Meus agendamentos** o cliente ganha o botão **Remarcar** (só em agendamento agendado) e o botão **Histórico**. Remarcar abre a escolha de outro dia e horário para o MESMO agendamento; o histórico lista o que aconteceu com ele. Só front: a API é a do PR 51.

## Decisões que valem entrevista
- **Os passos de dia e horário são os mesmos de agendar.** Extraí `DayStep` e `SlotStep` de `BookPage` para `BookingSteps.tsx` e os dois fluxos os usam: uma regra de apresentação, um lugar. Os testes antigos de agendar rodaram sem mudança de comportamento depois da extração.
- **Os horários ignoram o próprio agendamento** (`excludeAppointmentId`), então dá para deslocar de 10:00 para 10:30. A API só honra o parâmetro se o agendamento for do cliente; a tela só o envia.
- **Recusa por causa do horário mantém a caixa aberta** (horário tomado, no passado, perto demais, fora do expediente, mesmo horário): mostra o motivo, descarta a escolha e refaz a lista de horários. **Recusa por outro motivo** (prazo, limite de 3, agendamento que já começou ou foi alterado) fecha a caixa e põe o motivo na linha; se o agendamento mudou no servidor, a lista é refeita.
- **O agendamento movido, como a API devolveu, fica na tela mesmo que a lista seguinte falhe**, e vale só até uma carga NOVA da lista chegar (a versão da carga decide): se a equipe cancelou nesse meio tempo, a lista nova manda. O mesmo princípio do "estado confirmado guardado na tela" do cancelamento, sem esconder dado mais novo.
- **O cliente nunca vê o nome de um funcionário.** O histórico mostra "por você" ou "pelo estúdio"; mesmo que a API enviasse o nome (ela não envia ao cliente), a tela não o exibe. Teste com mutação.
- **Foco:** abrir a caixa e fechar por "Voltar" devolve o foco ao botão "Remarcar"; depois de remarcar, o foco vai para a linha, que mostra o horário novo.
- **Envio duplo:** dois cliques no mesmo instante, antes de a tela re-renderizar, remarcam uma vez só (single-flight); as escolhas ficam bloqueadas durante o envio.

## Testes
- `reschedule.test.tsx` (13): botão só nos agendados com fuso; abre na data do agendamento e pede horários ignorando o próprio; confirmar só habilita depois da escolha; "Voltar" e foco; envio do horário LOCAL, agendamento movido visível com a lista seguinte falhando, e a lista nova vencendo o estado local; horário tomado mantém a caixa e refaz os horários; recusas TOO_LATE, RESCHEDULE_LIMIT, CONCURRENT_UPDATE e NOT_RESCHEDULABLE (as duas últimas refazem a lista); duplo clique sincronizado; histórico em ordem e em português, sem nome de funcionário; erro do histórico.
- Suíte do front: 319 testes; tipos, lint e build verdes. Ajustei duas mensagens/asserções antigas (`TOO_LATE` agora diz "cancelar ou remarcar"; a linha de um agendamento cancelado continua sem botões de cancelar/remarcar, mas passou a ter "Histórico").
- **Mutação verificada** (7, todas derrubadas): horários sem ignorar o próprio agendamento; estado local vencendo a lista nova; recusa de horário fechando a caixa; Remarcar sem fuso; histórico com nome de funcionário; envio duplo (a 1ª versão do teste sobrevivia porque clicava num botão já bloqueado; reforçada com dois cliques sincronizados); estado que mudou sem refazer a lista.

## Limitações e o que falta
- **Não verifiquei em navegador real** (só jsdom), como nos PRs de pets.
- A tela da equipe (remarcar na agenda do dia, com os nomes no histórico) é o próximo PR.
- Sem teste de ponta a ponta com a API real nesta etapa.

## Perguntas de entrevista
1. Por que reutilizar os passos de dia e horário de agendar em vez de duplicar a tela? O que mudou nos horários (parâmetro) e por quê?
2. Por que o agendamento movido continua na tela até uma carga nova chegar, e por que não para sempre?
3. Quando a caixa de remarcar deve continuar aberta depois de uma recusa e quando deve fechar?
