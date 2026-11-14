# ADR 0010: catálogo de leitura para o cliente reservar

- **Status:** Accepted
- **Data:** 2026-11-14
- **Quem decidiu:** o Matheus delegou (modo autônomo noturno); desenho de Claude confirmado pelo ChatGPT (revisor independente), que classificou esta parte como esforço MÉDIO e pediu dados mínimos.

## Contexto
O cliente já consegue consultar horários livres e reservar pela API, mas precisa conhecer o id do prestador e do serviço. Falta o que leva o cliente até eles, sem expor dados que não são dele.

## Decisão
1. **Duas rotas de leitura só para CUSTOMER** (regra na rota e na anotação; ADMIN, PROVIDER e ATTENDANT recebem 403): `GET /api/catalog/providers?page&size` e `GET /api/catalog/providers/{id}/services`.
2. **Dados mínimos públicos:** prestador = `id`, `businessName`, `timeZone`; serviço = `id`, `name`, `durationMinutes`. Nada de e-mail, usuário dono, antecedência mínima, prazo de cancelamento nem qualquer dado administrativo; um teste confere o conjunto exato de campos.
3. **Só o que é ativo:** prestador cujo dono (usuário PROVIDER) está ativo e serviços ativos. Prestador com dono desativado não aparece na lista e, pelo id direto, responde 404 IGUAL ao inexistente (não revela que existe). Prestador sem serviços ativos aparece com lista vazia (a tela explica).
4. **Ordem estável e paginação:** nome sem diferenciar maiúsculas e depois o id; `size` de 1 a 50 (padrão 20), `page` a partir de 0; fora disso, 400.
5. **Limite por usuário:** as duas rotas usam o mesmo limite por minuto dos horários livres (`slots-per-minute`, 429 com `Retry-After`), para o catálogo não virar varredura em massa.
6. **`/api/appointments` com nomes:** as respostas de reservar, cancelar e de `/mine` agora trazem `businessName`, `timeZone` e `serviceName` (do histórico: continuam aparecendo se o prestador ou o serviço forem desativados depois), para o cliente ver o horário no fuso do negócio. Só aparecem agendamentos do próprio cliente (como antes).

## Alternativas descartadas
- **Catálogo público sem login:** exporia a lista de negócios a qualquer um e a varredura; o cadastro do cliente é rápido e o limite por usuário é mais fácil de aplicar.
- **Devolver o prestador completo e filtrar no front:** vazaria configuração interna e e-mail.
- **Filtrar por "tem expediente configurado":** esconderia negócios em fase de configuração de forma surpreendente e custaria consultas a mais; a tela de horários já mostra "sem horários".

## Consequências e limitações conhecidas
- Não há busca por nome nem filtro por cidade (virá se a lista crescer).
- O fuso do prestador é exposto ao cliente de propósito (necessário para mostrar o horário local).
