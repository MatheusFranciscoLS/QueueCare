# Estudo: front pronto para publicar (PR 33)

## Em linguagem simples
Quando o site e a API ficam em endereços diferentes (como na hospedagem gratuita), o front precisa saber onde está a API. Também precisa avisar a pessoa quando o servidor está "acordando", para ela não achar que quebrou.

## O que existe
- `VITE_API_URL`: endereço da API, definido na hora do build. Vazio em desenvolvimento.
- Valor inválido (sem https, com caminho, com parâmetros) FALHA na hora, em vez de mandar o token para o lugar errado.
- Faixa "Isto está demorando mais que o normal..." quando uma resposta passa de 4 segundos; some quando chega ou falha.

## Como ler o código
`api/base.ts` (endereço), `api/slow.ts` (contagem de requisições lentas), `pages/WakingBanner.tsx`, e a chamada única em `api/client.ts`.

## Provas por teste (250 no front, 13 novos em `publication.test.tsx`)
- Sem variável o caminho segue relativo; com variável junta origem e caminho (com ou sem barra final); 6 valores inválidos falham; o cliente HTTP usa o endereço.
- Requisição rápida nunca mostra o aviso; lenta mostra e some ao terminar; várias lentas só somem quando todas terminam; terminar duas vezes não desbalanceia; o cliente libera ao chegar ou ao falhar.
- Mutações verificadas: sem liberar a contagem, cliente ignorando a base e sem rastreio: cada uma derruba testes.

## Três perguntas de entrevista
1. **Por que um endereço inválido falha em vez de seguir com o caminho relativo?** Porque seguir enviaria o token e os dados a um servidor diferente do que a pessoa configurou; falhar alto é mais seguro.
2. **Por que o aviso conta requisições e não usa um relógio global?** Cada requisição lenta conta uma vez e é descontada ao terminar; assim o aviso some exatamente quando a última lenta acaba.
3. **Por que não guardar a URL da API no código?** Cada ambiente tem a sua; ela entra no build por variável, sem mudar o código.

## Limitações
- O aviso aparece para qualquer requisição lenta, não só para cold start (o texto explica o caso mais provável).
