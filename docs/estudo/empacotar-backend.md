# Estudo: empacotar o backend para publicar de graça (PR 34)

## Em linguagem simples
Para o backend rodar num servidor gratuito ele precisa virar uma "caixa" (imagem Docker) que funciona igual em qualquer lugar, com as senhas fora da caixa. Este PR cria a caixa, a configuração de produção e um roteiro para você publicar sem cartão.

## O que existe
- **Dockerfile** em duas etapas (compila com o JDK, roda só com o JRE), como usuário comum, sem nenhum segredo, com as imagens-base fixadas por **digest**.
- **Perfil `prod`:** porta que a plataforma informa, pool de conexões pequeno (3), migrations pela conexão DIRETA do banco (sem pooler) e só o health exposto.
- **`render.yaml`:** só plano gratuito, todos os segredos preenchidos no painel, publicação manual.
- **Health separado:** `/actuator/health/liveness` (só o processo, o Render usa este) e `/actuator/health/readiness` (inclui o banco). Banco dormindo não derruba a API.
- **CI:** um job constrói a imagem e confere que o UID é 10001 (não root).
- **`docs/publicacao.md`:** passo a passo para criar as contas sem cartão, variáveis, primeiro administrador sem deixar senha no histórico do terminal, desligar tudo.

## Como medi (não é chute)
Imagem real + Postgres 16 em contêiner + limites do Render (512 MB, 0,1 CPU): com as flags comuns sobe em ~187 s e usa ~310 MB; com só o compilador leve do JIT sobe em **~95 s** e usa **~250 MB**. Cadastro e login levam 3 a 5 s nessa CPU. Por isso a primeira visita depois de uma pausa demora de um a dois minutos e o site avisa.

## Provas por teste
- `DeploymentFilesTest` (5): só `plan: free`, nenhum valor literal e todo segredo `sync: false`; health do Render é a vivacidade; Dockerfile com usuário comum, sem segredos, com as duas imagens fixadas por digest; CI exige o UID exato 10001; perfil prod com pool 3, `PORT`, Flyway direto e só health; configuração base sem padrão para `JWT_SECRET` e senha do banco, OpenAPI desligado.
- `HealthProbesIntegrationTest`: com o banco "caído", agregado e prontidão dão 503 e a **vivacidade dá 200**.
- Mutações verificadas: plano pago, tirar o `USER`, Flyway pelo pooler, health check no agregado, imagem sem digest, CI só "diferente de 0": cada uma derruba um teste.

## Três perguntas de entrevista
1. **Por que o health check da plataforma não deve consultar o banco?** Se o banco estiver acordando ou fora, a plataforma acharia a API morta e a reiniciaria em laço, piorando tudo; vivacidade diz "o processo está vivo" e prontidão diz "pode atender".
2. **Por que o Flyway usa a conexão direta e a aplicação usa o pooler?** Migrations e bloqueios dependem de sessão; o pooler em modo transação não os garante. A aplicação só usa bloqueios transacionais (auditados), então o pooler serve a ela.
3. **Por que fixar a imagem-base por digest?** A tag `21` muda com o tempo; o digest identifica exatamente o que foi publicado e o CI avisa quando o arquivo for alterado.

## Limitações
- As medidas são do Docker local; reconferir no Render. A imagem não é publicada automaticamente (de propósito).
- O roteiro depende de o Matheus criar as contas; nada foi cadastrado.
