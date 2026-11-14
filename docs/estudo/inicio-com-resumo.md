# Estudo: início com resumo do dia

## O que é
A primeira tela depois do login passou de "lista de links" para um painel: quantos agendados, quantos na fila, quantos concluídos hoje e quem é o próximo. Os números vêm das mesmas APIs da Agenda e da Fila.

## Decisões
- **Sem backend novo:** `summarize` (função pura em `api/summary.ts`) conta no cliente. Para o tamanho de uma agenda diária de um pequeno negócio isso é suficiente; um endpoint de resumo só valeria se o volume crescesse.
- **Hoje no fuso do negócio**, nunca do aparelho (`todayIn`).
- **Falha isolada:** o resumo carrega separado dos atalhos; erro mostra aviso e botão, 403 (sem vínculo) e "não configurado" somem em silêncio.
- **"Próximo" = primeiro agendado ou já chegado cujo fim ainda não passou**, em ordem de horário.

## Testes
`today-summary.test.tsx`: contagem e "próximo" (puro), painel completo, cliente sem painel, 403, falha e nova tentativa. Mutação: remover o filtro de fim do atendimento e remover o tratamento do 403 fazem testes falharem.

## Perguntas de entrevista
1. Por que calcular o resumo no cliente e quando mudaria para um endpoint próprio?
2. Por que o dia de "hoje" usa o fuso do negócio, e o que acontece perto da meia-noite?
3. Como evitar que uma falha em um bloco da tela derrube a tela inteira?
