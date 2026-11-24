# ADR 0036: log de acesso (método, rota, status e duração)

- **Status:** Accepted (implementado em 24/11/2026)
- **Data:** 24/11/2026
- **Esforço:** BAIXO (um método no filtro que já existia, 4 testes)

## Contexto: a análise crítica
O ADR 0029 deu a cada chamada um **código de requisição** que aparece em toda linha de log e volta no cabeçalho `X-Request-Id`. Isso responde "o que aconteceu nesta chamada?" quando alguém informa o código. Não responde duas perguntas que a operação faz antes: **"quais chamadas estão falhando?"** e **"quais estão lentas?"**. Sem uma linha por chamada com status e duração, só se acha um problema olhando linha por linha, ou quando alguém reclama.

## Decisão
1. **Uma linha por chamada**, no fim dela: `GET /api/customer/pets/{id} -> 404 em 12 ms`, no mesmo filtro do código de requisição (então o código vai junto, pelo MDC, e o log de acesso se cruza com todas as outras linhas daquela chamada).
2. **A rota é o PADRÃO, nunca o caminho real.** `{id}` no lugar do identificador, e nada de texto de consulta. O nome ou o telefone buscado numa tela, ou o id de um tutor, não entram no log. Chamadas recusadas pelos filtros (401, 403, 429) não chegam a ter rota e saem como `(sem rota)`: o caminho real delas também não é registrado.
3. **Nada além disso**: nem cabeçalhos (a Authorization em especial), nem token, nem corpo, nem o usuário. Há teste que procura o identificador, o texto buscado e o token na saída e exige que não existam.
4. **Duração por relógio monotônico** (`System.nanoTime`), não pela diferença de relógios de parede, que pode ser ajustado.
5. **Erro do servidor (5xx) sai em `WARN`**; o resto em `INFO`. O logger tem nome próprio (`queuecare.access`), então dá para ligar, desligar ou mudar o nível por configuração sem mexer no resto.
6. **As verificações de saúde (`/actuator/**`) ficam de fora**: o Render e o monitor do ADR 0032 chamam o tempo todo e afogariam o que importa.

## O que foi descartado
- **Prometheus, Grafana, Micrometer exposto e tracing distribuído:** exigem processos e memória que o plano gratuito não comporta e que um único serviço não justifica. Uma linha de log estruturada o bastante responde às perguntas do dia a dia, com custo zero.
- **Log em JSON:** ajuda quando há um coletor; aqui o log é lido na tela do Render, e uma linha curta é mais legível. O formato fixo (`método rota -> status em N ms`) é fácil de filtrar com `grep`.
- **Registrar o usuário e o corpo para "facilitar o diagnóstico":** é exatamente o tipo de dado pessoal que não pode parar num log.

## Riscos conhecidos
- **Sem percentis nem gráficos:** é preciso contar linhas para saber a taxa de erro ou a duração típica. Basta para o tamanho do projeto; um painel seria o passo seguinte, se houver necessidade.
- **A retenção do log é a do Render gratuito** (curta): serve para diagnosticar o presente, não para análise histórica.
- **Rota sem controlador** (404 de uma URL inexistente) também sai como `(sem rota)`, o que esconde tentativas de varredura por caminho; é o preço de não registrar o caminho real.

## Consequências
- "Quais chamadas estão dando 5xx?" e "qual rota demora?" passam a ter resposta em uma busca no log.
- Nenhum dado pessoal novo no log, nenhum custo, nenhuma dependência.
