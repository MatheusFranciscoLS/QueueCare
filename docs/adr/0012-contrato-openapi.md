# ADR 0012: contrato OpenAPI gerado do código

- **Status:** Accepted
- **Data:** 2026-11-14
- **Quem decidiu:** o Matheus delegou (modo autônomo, esforço MÉDIO); desenho de Claude, revisão do ChatGPT.

## Contexto
A API tem 30 caminhos e regras de perfil por endpoint. Falta um contrato legível por pessoas e ferramentas que NÃO possa divergir do que o código faz.

## Decisão
1. **Gerado do código** com `springdoc-openapi-starter-webmvc-api` (só o módulo da API: sem interface web nem webjars, menos superfície). Endpoints, DTOs (records), parâmetros e validações saem do próprio código.
2. **Quem pode chamar cada operação vem da regra REAL:** um `OperationCustomizer` lê o `@PreAuthorize` do método ou da classe e escreve "Perfis: ..." na descrição. **Só `hasRole('X')` e `hasAnyRole('X', 'Y')` são entendidos; qualquer outra expressão FAZ A GERAÇÃO FALHAR** (nunca publica descrição enganosa; quem usar outra forma ensina o gerador); rotas sem regra são "Qualquer usuário autenticado", e só `login` e `register` do `AuthController` são "Público" (`security: []`). A descrição não consegue discordar da autorização.
3. **Erros documentados FIÉIS ao que a API entrega** (provado por teste de resposta real): **401** e **403 por perfil** vêm da cadeia de segurança do Spring com CORPO VAZIO (401 com `WWW-Authenticate: Bearer`); **403 por troca de senha obrigatória** e **429** (com `Retry-After`) são `application/problem+json`. O esquema `Problem` tem todos os campos de `ProblemDetail` (`type`, `title`, `status` inteiro, `detail`, `instance`) mais `reason` e `errors`.
4. **O arquivo `docs/openapi.json` é o contrato publicado** (versionado, ordem estável, LF) e é **conferido por teste**: mudar endpoint, DTO ou perfil sem atualizar o arquivo quebra o CI. Atualizar: `./mvnw test -Dtest=OpenApiContractTest -Dopenapi.update=true` e revisar o diff.
5. **Em execução o `/v3/api-docs` fica DESLIGADO** (`OPENAPI_ENABLED=false` por padrão) e, quando ligado, exige token como qualquer rota; Swagger UI desligado. A API não se descreve sozinha em produção; quem quer ver o contrato abre o arquivo (por exemplo no editor do Swagger).
6. Testes: esquema `Problem` com todos os campos e tipos; respostas 401, 403, 429 reais (status, Content-Type, cabeçalhos e corpo, em `OpenApiErrorBehaviorTest`); extrator de perfis recusa expressão desconhecida (`RoleExpressionTest`); arquivo igual ao gerado; toda operação diz quem pode chamar e os perfis batem com a regra (admin, check-in, minhas, fila); só login e cadastro são públicos; o contrato não expõe senha, hash nem versão das credenciais; erros comuns presentes; desligado por padrão (404 para ADMIN, 401 sem token, sem Swagger).

## Alternativas descartadas
- **Escrever o OpenAPI à mão:** divergiria do código.
- **Swagger UI ligado em produção:** expõe a API sem necessidade e puxa mais dependências; o arquivo estático basta.
- **Anotar cada endpoint com `@Operation`:** muito texto repetido; os perfis são derivados e o resto sai do código.

## Consequências e limitações
- Descrições de campos e exemplos ainda são mínimos (vêm dos nomes e validações); podem ser enriquecidos com `@Schema` sem mudar o desenho.
- O contrato descreve respostas de sucesso e os erros comuns; os `reason` específicos de cada rota estão nos ADRs e nos estudos.
