# ADR 0026: desfazer a última etapa do atendimento

- **Status:** Accepted (implementado em 21/11/2026, terceiro item do plano de prontidão para uso real)
- **Data:** 21/11/2026
- **Esforço:** PEQUENO a MÉDIO (uma migração, um método de serviço, uma rota, um botão em duas telas)

## Contexto: a análise crítica
O balcão é corrido e o celular é pequeno: tocar em **Chegou** no cliente errado, ou em **Pronto** antes da hora, é inevitável. As transições do atendimento só andam para frente (ADR 0011 e 0019) e o banco impõe **um evento de cada tipo por agendamento** (a fila lê o evento de chegada). Resultado: um toque errado não tinha volta pela interface, e a única saída era mexer no banco, que o estúdio não faz. Um "tem certeza?" antes de cada ação não resolve: cansa e as pessoas confirmam no automático.

## Decisão
1. **Desfazer volta UMA etapa**, só de atendimento **em andamento**: pronto → em atendimento (ou chegou, se "começar" foi pulado); em atendimento → chegou; chegou → agendado. A ação fica em **Mais ações** ("Desfazer chegada", "Desfazer início", "Desfazer pronto"), na Agenda do dia e na Fila, e só a equipe do próprio estúdio a usa.
2. **Estados finais não voltam:** concluído, faltou e cancelado respondem 409 (cancelado, `ALREADY_CANCELLED`; os outros, `INVALID_STATE`). Concluir mexe no fechamento do dia e faltou conta contra o tutor (ADR 0021): corrigir isso é decisão de gente, não um toque.
3. **O histórico guarda a correção.** O evento da etapa desfeita é **removido** (senão a etapa não poderia ser refeita: o índice único de um evento por tipo) e entra um evento **`UNDONE`**, que pode se repetir, com quem desfez, quando e qual etapa era (em `notes`). O histórico mostra "Etapa desfeita (chegada) por Maria". Quem refaz a chegada é **o último da fila**: a nova chegada tem hora nova.
4. **Sem janela de tempo.** Voltar uma etapa de quem ainda está em andamento é inofensivo mesmo horas depois, e uma janela obrigaria a tela a saber a hora de cada evento só para esconder um botão.

## O que isto NÃO é
- Não é um "desfazer" genérico com pilha: volta uma etapa por vez, quantas vezes forem necessárias.
- Não apaga o agendamento nem mexe em sinal, lembrete, preço ou lista de espera.
- Não permite reabrir um atendimento concluído. Se virar necessidade real, o caminho é uma ação própria, com motivo e janela, não afrouxar esta.

## Consequências
- Toque errado em Chegou/Começar/Pronto se corrige em dois toques, sem suporte técnico.
- A tabela de eventos deixa de ser estritamente de acréscimo para esses três tipos: a etapa desfeita some e o `UNDONE` conta que aconteceu. A troca é consciente: manter o evento antigo exigiria abrir mão do índice único que a fila usa.
- A tela da Agenda, que só guarda localmente o estado mais avançado, passa a limpar essa memória ao desfazer (senão mostraria o estado antigo até a próxima carga).
- Testes: 7 de integração (cada etapa, refazer, fila, estados finais, isolamento entre estúdios, histórico para equipe e tutor) e 5 de tela.
