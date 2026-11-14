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

## Consequências
- Um novo pacote de fontes (peso extra no carregamento, ~dezenas de KB por idioma usado).
- Os contrastes dos pares texto/fundo foram escolhidos para AA, mas **ainda não há medição automática**; fica como tarefa da etapa de testes.
- O modo escuro é automático (sem botão); botão manual fica para depois.
