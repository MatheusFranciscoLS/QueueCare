# Estudo: telas de chegada, conclusão e fila (PR 27, front do M4)

## Em uma frase
A equipe marca "Chegou" e "Concluir atendimento" na agenda e acompanha a fila de hoje, sem tempo real: a fila se atualiza quando a pessoa pede.

## O que existe
- **Agenda:** botão "Chegou" nos agendados de HOJE (no fuso do negócio); "Concluir atendimento" em quem chegou; quem chegou não pode ser cancelado.
- **Fila** (`/fila`, dono e atendente): quem chegou hoje, por ordem de chegada, hora da chegada no fuso do negócio, botão "Atualizar a fila" e "Concluir atendimento".
- Mensagens para `WRONG_DAY`, `INVALID_STATE` e as demais recusas.

## Como ler o código
1. `api/agenda.ts` (`queue`, `checkIn`, `complete`) e `api/messages.ts`.
2. `pages/agenda/AgendaPage.tsx` (linha com as ações) e `pages/agenda/QueuePage.tsx`.

## Provas por teste (208 no front)
- Chegada e conclusão: situação nova na hora mesmo se o GET seguinte falhar; lista de antes da chegada não desfaz a chegada; chegada só no dia de hoje; duplo clique envia um pedido; botões bloqueados durante o envio.
- Recusas: `WRONG_DAY` não refaz a lista; `INVALID_STATE`, `ALREADY_CANCELLED` e `CONCURRENT_UPDATE` refazem e a linha passa a valer o que o servidor diz; **o motivo continua na tela mesmo quando a linha some** (defeito que o próprio teste revelou).
- Fila: ordem e hora no fuso; vazia; atualizar; concluir tira da fila na hora e refaz as posições; erro ao carregar; sem vínculo; sem negócio; cliente e ADMIN não veem o menu nem chamam a fila.
- Foco: a confirmação de cancelar abre em "Voltar"; após a ação, o foco vai para a linha.
- Mutações verificadas: sem refetch, sem guardar a situação, chegada em qualquer dia e sem remoção local da fila: cada uma derruba testes.

## Três perguntas de entrevista
1. **Por que a mensagem de erro fica na lista e não na linha?** Depois de um erro de estado a lista é refeita e a linha pode desaparecer; o aviso sumiria junto.
2. **Por que guardar a situação confirmada na tela?** O GET seguinte pode falhar ou trazer um retrato de antes da mudança; a tela não pode voltar atrás sozinha.
3. **Por que não há tempo real?** O escopo do MVP é fila simples; "Atualizar a fila" é explícito e evita websocket, reconexão e sincronização entre aparelhos.

## Limitações
- A fila só muda quando alguém atualiza; sem "faltou" (no-show); chegar e concluir não pedem confirmação.
