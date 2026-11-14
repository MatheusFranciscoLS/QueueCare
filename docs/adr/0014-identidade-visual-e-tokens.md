# ADR 0014: identidade visual e tokens de design

- **Status:** Accepted
- **Data:** 14/11/2026
- **Quem decidiu:** o Matheus pediu melhorar o visual e as funções; auditoria visual de Claude (40 capturas reais) e do ChatGPT (9 capturas), que deram ao visual nota 5,5/10 e à experiência 6/10.

## Contexto
O CSS era um arquivo único com cores soltas, fonte do sistema e alvos de toque pequenos. Funcionava, mas parecia um sistema interno genérico e não comunicava o estado do atendimento.

## Decisão
1. **Sem biblioteca de UI.** Continua CSS próprio, agora organizado em **tokens** (variáveis CSS) de cor, tipo, espaço, raio e sombra em `frontend/src/index.css`. Tema claro e escuro seguem o sistema, com os mesmos componentes recebendo valores diferentes.
2. **Paleta própria:** verde-petróleo (`--accent`) como cor de ação, para passar calma e confiança; **âmbar** reservado ao estado "esperando"; azul para "agendado", verde para "concluído", vermelho só para erro e cancelamento destrutivo. Cada estado tem texto e fundo (selo legível sem depender só da cor: o texto do selo continua dizendo o estado).
3. **Tipografia:** Inter (interface) e Manrope (títulos), hospedadas junto do site pelos pacotes `@fontsource-variable`: nenhum pedido a terceiros, custo zero.
4. **Assinatura visual: a Linha de Atendimento.** Este PR só planta a marca (três pontos: em atendimento, esperando, depois) e os selos de estado; a linha do tempo da agenda vem nos PRs seguintes.
5. **Acessibilidade:** alvos de toque de pelo menos 44 px, foco visível de 3 px, `prefers-reduced-motion` respeitado, menu que rola na horizontal em vez de quebrar linhas.
6. **Migração gradual:** nomes de classe existentes foram mantidos; nenhuma regra de negócio, API nem fluxo mudou. Cada tela é migrada em um PR pequeno.

## Consequências e medições (revisão do PR 37)
- Fontes: o navegador só baixa os subconjuntos usados (unicode-range); para português, **latin**: Inter 48 KB + Manrope 25 KB em WOFF2 (~73 KB), medido no build. O build emite também os outros subconjuntos, que não são baixados.
- **Contraste medido (WCAG 2.1):** pares texto/fundo dos dois temas e dos quatro estados: de 5,0:1 a 15,3:1 (AA). Correções da revisão: texto do botão perigoso no tema escuro (`--on-danger`, de 2,28:1 para 8,03:1) e borda de campos (`--field-line`, 3,5:1 no claro e 3,8:1 no escuro; antes ~1,4:1). Bordas decorativas de cartões e linhas continuam em `--line`.
- O foco visível de 3 px (`:focus-visible`) **já existia**: foi preservado, não criado aqui. No menu rolável usa deslocamento interno para não ser recortado.
- Caixas e opções de marcar mantêm o desenho nativo (só recebem `accent-color`).
- Ainda não há teste automático de contraste nem de foco em navegador real; entra na etapa de testes ponta a ponta.
- O modo escuro é automático (sem botão); botão manual fica para depois.

## Atualização (PR 2/10): a Linha de Atendimento
A agenda e a fila passam a usar a lista `.timeline`: um traço vertical com um ponto por atendimento, na cor do estado (agendado, chegou, concluído). O estado continua escrito no selo (a cor nunca é a única pista). A marcação ganhou `appt-info/appt-time/appt-who/appt-service`; separadores " · " ficam no DOM, ocultos na tela e para leitores de tela (`aria-hidden`), para a leitura em texto das linhas não mudar.
- (revisão do PR 38) O ponto da Linha de Atendimento é ancorado à primeira linha de `.appt-info`, não ao centro da `<li>`: assim ele não se desloca quando as ações quebram de linha ou a confirmação de cancelamento abre. O traço do primeiro e do último item termina em `--dot-y`. Conferido em navegador real no celular, com a confirmação aberta.


## Atualização (PR 3/10): entrada e carregamento
Um componente único `Loading` (`pages/Loading.tsx`) substitui os "Carregando…" soltos: mantém `role="status"` e o texto (leitores de tela e testes) e acrescenta três pontos animados, decorativos (`aria-hidden`), que respeitam `prefers-reduced-motion`. Login e cadastro ganham faixa de destaque no cartão e uma frase de apresentação. Nenhuma regra de negócio mudou.
- (revisão do PR 39) Com `prefers-reduced-motion: reduce` os pontos de carregamento ficam parados (`animation: none`), pois encurtar só a duração não para uma animação infinita; verificado em navegador real nos dois modos. O cadastro também ganhou a frase de apresentação.

## Atualização (PR 4/10): início com resumo do dia
A tela inicial do prestador e do atendente mostra o resumo de hoje (agendados, na fila, concluídos e o próximo atendimento), usando só as APIs que já existem (workplace, agenda do dia, fila): nenhuma regra nova no backend. O dia é o de hoje NO FUSO DO NEGÓCIO. Falha no resumo nunca esconde os atalhos: avisa e oferece "Atualizar resumo"; atendente sem vínculo e prestador sem negócio não veem o resumo (as telas de Agenda e Fila já explicam). Os atalhos viraram cartões com descrição. A lógica de contagem fica em `api/summary.ts` (função pura, testada, com mutação verificada).
- (revisão do PR 40) O resumo acompanha o relógio com a tela aberta: a cada minuto (e ao voltar à aba) recalcula o "próximo" e, se o dia **do negócio** virou, refaz a leitura (tenta de novo a cada minuto enquanto falhar). São 3 chamadas por visita (workplace, agenda do dia, fila): aceitável para o porte do produto; um endpoint de resumo só se o uso crescer.
