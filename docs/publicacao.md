# Roteiro de publicação gratuita (ADR 0013)

**Regra do dono: custo zero e NENHUM cartão.** Em qualquer passo, se um site pedir cartão, **pare e avise**: não cadastre nada e não troque de provedor por conta própria. Os planos gratuitos mudam: confira a página oficial de cada serviço na hora.

Arquitetura: **Cloudflare Pages** (site) + **Render Free** (API) + **Neon Free** (PostgreSQL).

## O que já está pronto no repositório
- `backend/Dockerfile` e `render.yaml` (plano gratuito, segredos só no painel), `application-prod.yml` e o front com `VITE_API_URL` e aviso de "servidor acordando".
- **Medido no Docker com os limites do Render (512 MB, 0,1 CPU):** a API sobe em ~95 s, usa ~250 MB (cabe) e um cadastro ou login leva 3 a 5 s por causa da CPU limitada. Por isso a primeira visita depois de uma pausa demora de um a dois minutos, e o site avisa.

## Parte 1: contas (só você pode fazer) 
Para cada serviço, entre com o e-mail ou o GitHub, **não cadastre cartão**. Se pedir, pare.
1. **Neon** (neon.com): criar conta e um projeto PostgreSQL 16. Anote: a URL **com pooler** (host terminado em `-pooler`) e a URL **direta** (sem `-pooler`), o usuário e a senha.
2. **Render** (render.com): criar conta sem cartão. Não adicione meio de pagamento em nenhum momento.
3. **Cloudflare** (pages): criar conta e conectar o GitHub ao repositório.

## Parte 2: banco (Neon)
- As migrations criam a extensão `btree_gist` sozinhas (V1). Elas rodam na PRIMEIRA subida da API, pela URL direta.
- URLs no formato JDBC, com SSL: `jdbc:postgresql://HOST/NOMEDOBANCO?sslmode=require`. Na URL do pooler acrescente `&prepareThreshold=0`.

## Parte 3: API (Render)
1. Novo **Blueprint** apontando para o repositório (usa `render.yaml`) ou serviço web com Docker: caminho `backend/Dockerfile`, contexto `backend`, plano **Free**.
2. Variáveis (todas secretas, só no painel):

| Variável | Valor |
|---|---|
| `DB_URL` | URL JDBC do Neon **com pooler** |
| `DB_DIRECT_URL` | URL JDBC do Neon **direta** (migrations) |
| `DB_USER` / `DB_PASSWORD` | usuário e senha do Neon |
| `JWT_SECRET` | gerar uma vez: `openssl rand -base64 32` (ou `node -e "console.log(require('crypto').randomBytes(32).toString('base64'))"`); **fixo**: trocar invalida todos os logins |
| `CORS_ALLOWED_ORIGINS` | só o endereço do site, ex.: `https://queuecare.pages.dev` |

3. Health check: `/actuator/health` (já no `render.yaml`). A primeira subida leva uns 2 minutos.

## Parte 4: primeiro administrador (ADR 0005)
O plano gratuito do Render não dá terminal. Crie o admin **do seu computador**, uma única vez, apontando para o Neon pela URL direta:
```bash
cd backend && ./mvnw -q -DskipTests package
ADMIN_EMAIL=seu@email ADMIN_PASSWORD='uma-senha-forte-de-12+' \
DB_URL='jdbc:postgresql://HOST-DIRETO/DB?sslmode=require' DB_DIRECT_URL="$DB_URL" DB_USER=... DB_PASSWORD=... JWT_SECRET=... \
SPRING_PROFILES_ACTIVE=prod \
java -jar target/queuecare-api-*.jar --queuecare.bootstrap-admin.enabled=true --server.port=0
```
Saída "ADMIN criado" = pronto. Depois **apague** as variáveis do terminal (`unset ADMIN_PASSWORD`). A senha só vale para o primeiro login (a troca é obrigatória).

## Parte 5: site (Cloudflare Pages)
- Projeto a partir do repositório; **diretório raiz:** `frontend`; **comando de build:** `npm ci && npm run build`; **saída:** `dist`.
- Variável de build: `VITE_API_URL` = endereço da API no Render (só a origem, `https://...`).
- Depois de publicar, coloque o endereço do site em `CORS_ALLOWED_ORIGINS` no Render e reinicie a API.
- Apenas hospedagem estática: não use Pages Functions nem Workers.

## Parte 6: conferir
1. Abra o site: a primeira resposta pode demorar (aviso amarelo). Entre com o admin e troque a senha.
2. Crie um prestador pelo menu do administrador e teste o fluxo.
3. Painéis de uso do Neon e do Render: confira todo mês os limites (Neon 100 CU-h e 5 GB de transferência; Render 750 h).

## Desligar tudo (quando quiser)
Exportar o banco (`pg_dump`) → excluir o serviço no Render → excluir o projeto no Neon → excluir o projeto na Cloudflare → **revogar os tokens e apagar os segredos** do GitHub → conferir que nada ficou ativo.

## Se algo não couber
- O Render pedir cartão, ou a API não subir com 512 MB: **parar**. Alternativa honesta: publicar só o site com dados simulados (com aviso) e gravar um vídeo do sistema rodando no computador.
