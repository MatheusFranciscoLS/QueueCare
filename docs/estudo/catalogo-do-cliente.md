# Estudo: catálogo do cliente (PR 23, parte A da reserva)

## Em uma frase
O cliente agora consegue descobrir onde agendar sem receber nada além do necessário.

## O que existe
- `GET /api/catalog/providers` e `GET /api/catalog/providers/{id}/services`, só para CUSTOMER.
- `/api/appointments` (reservar, cancelar, meus agendamentos) com nome do negócio, fuso e nome do serviço.
- Limite por usuário nas rotas do catálogo.

## Como ler o código
1. `CatalogService` (regras) e `CatalogController` (rotas).
2. `ProviderRepository.findAllActive` e `ServiceOfferingRepository.findByProviderIdAndActiveTrue...`.
3. `AppointmentController.respond` (nomes do histórico).

## Provas por teste (412 no backend)
- Lista só prestadores com dono ativo, em ordem estável sem diferenciar maiúsculas; desativar o dono tira da lista.
- Campos EXATOS: id, nome e fuso (prestador); id, nome e duração (serviço); nenhum e-mail ou dado interno.
- Prestador desativado e inexistente respondem 404 idêntico; serviço desativado nunca aparece; prestador sem serviços devolve lista vazia.
- Paginação e limites (size 1 a 50, página negativa, página além do fim); id inválido é 400 sem vazar exceção.
- Só CUSTOMER lê; os outros perfis 403, anônimo 401, conta com troca pendente 403, conta inativa 401.
- Histórico do cliente com nomes mesmo depois de desativar prestador e serviço, e sem agendamentos de outro cliente.
- Limite por usuário (429 na quarta consulta; outro usuário não é afetado).
- Mutações: sem o filtro de serviço ativo e sem o de prestador ativo, os testes falham.

## Três perguntas de entrevista
1. **Por que o prestador desativado responde 404 e não 403?** Para não revelar que o negócio existe a quem não deveria saber; é a mesma resposta do inexistente.
2. **Por que expor o fuso do prestador ao cliente?** O horário é guardado em UTC; para mostrar "10h" é preciso o fuso do negócio.
3. **Por que limitar uma rota de leitura?** Para evitar varredura em massa do catálogo e uso abusivo.

## Limitações e o que falta
- Telas de escolha e de reserva (próximos PRs); busca e filtros quando a lista crescer.
