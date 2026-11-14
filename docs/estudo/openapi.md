# Estudo: contrato OpenAPI (PR 31)

## Em linguagem simples
Um arquivo (`docs/openapi.json`) descreve TODA a API: caminhos, dados, quem pode chamar. Ele é gerado do código e um teste garante que está em dia, então não dá para a documentação mentir.

## Como usar
- Abrir `docs/openapi.json` em um visualizador OpenAPI (por exemplo o editor online do Swagger) ou importar no Postman/Insomnia.
- Atualizar depois de mudar a API: `./mvnw test -Dtest=OpenApiContractTest -Dopenapi.update=true` e revisar o diff do arquivo no PR.
- Ver ao vivo (só desenvolvimento): subir com `OPENAPI_ENABLED=true` e chamar `/v3/api-docs` com um token.

## O que os testes provam (6 + 1 + 4 + 2)
- O arquivo publicado é igual ao contrato gerado (mutação trocando um perfil no código derruba este teste).
- Toda operação diz quem pode chamar, vindo do `@PreAuthorize` real.
- Só login e cadastro são públicos; nada sensível (senha, hash, versão das credenciais) aparece.
- O esquema de erro tem todos os campos com os tipos certos; 401 e 403 por perfil têm corpo vazio, 403 de troca de senha e 429 são problem+json: o contrato diz exatamente isso e testes de resposta real conferem.
- O gerador FALHA diante de uma expressão `@PreAuthorize` que não entende.
- Desligado por padrão (mutação ligando por padrão derruba o teste).

## Três perguntas de entrevista
1. **Como a documentação não diverge do código?** É gerada dele e o CI compara com o arquivo versionado; os perfis saem da mesma anotação que protege a rota.
2. **Por que não deixar o Swagger UI ligado?** Expõe a API a quem não precisa e aumenta a superfície; o arquivo estático dá o mesmo valor ao leitor.
3. **Por que o arquivo usa LF e ordem de chaves fixa?** Para o diff do PR mostrar só o que mudou de verdade, em qualquer sistema.

## Limitações
- Poucos exemplos e descrições de campo; os `reason` por rota ficam nos ADRs e estudos.
