# Estudo: identidade visual e tokens de design

## O que é
Um conjunto de **tokens** (variáveis CSS como `--accent`, `--space-4`, `--radius-md`) que guardam as decisões visuais em um só lugar. Os componentes (botão, cartão, selo, menu) só usam tokens; trocar o tema ou a paleta é mexer em poucas linhas.

## Por que assim
- Telas consistentes sem biblioteca pesada: CSS próprio já bastava para o MVP.
- Tema escuro quase de graça: os mesmos componentes recebem outros valores dentro de `@media (prefers-color-scheme: dark)`.
- Estados do atendimento têm cor própria (`data-status="CHECKED_IN"` etc.), então a agenda comunica quem chegou sem o usuário ler tudo.
- Acessibilidade como piso: alvo de 44 px, foco visível, movimento reduzido.

## Como ler o código
`frontend/src/index.css` (tokens no topo), `Layout.tsx` (marca de três pontos), selos com `data-status` em `AgendaPage.tsx` e `MyAppointmentsPage.tsx`.

## Perguntas de entrevista
1. Por que usar variáveis CSS em vez de uma biblioteca de componentes, e quando eu trocaria de ideia?
2. Como você garante que a informação de status não dependa só da cor (daltonismo)?
3. Como o tema escuro funciona sem duplicar o CSS, e o que ainda falta para um botão de tema manual?
