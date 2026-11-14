# ADR 0013: hospedagem com custo zero e sem cartão

- **Status:** Proposed (depende de o Matheus criar as contas; nada foi cadastrado e nenhum cartão foi usado)
- **Data da pesquisa:** 14/11/2026. **Os planos gratuitos mudam: reconferir a página oficial de cada serviço na hora de criar a conta.**
- **Quem decidiu:** o Matheus exigiu CUSTO ZERO e NENHUM CARTÃO (sem servidor comprado), o que descarta a AWS do roadmap (M5). Desenho de Claude, pesquisa e revisão do ChatGPT.

## Contexto
O projeto é de portfólio: pode "dormir" quando ninguém usa, mas não pode gerar cobrança nem pedir meio de pagamento. O banco precisa ser PostgreSQL de verdade (extensão `btree_gist`, restrição de exclusão, bloqueios de linha).

## Decisão
1. **Arquitetura-alvo:** frontend estático na **Cloudflare Pages**; backend (Spring Boot, Java 21) no **Render Free**; banco no **Neon Free**. Em todos: **nenhum meio de pagamento cadastrado.**
2. **Banco (Neon Free):** PostgreSQL real; limites a registrar e respeitar: **1 GB** de armazenamento, **100 CU-horas/mês**, **5 GB de transferência/mês**, compute suspende após 5 min sem uso. Ao atingir um limite o serviço pode parar até renovar o mês.
3. **Backend (Render Free):** documentação oficial diz que o cadastro e o serviço gratuito dispensam pagamento; **512 MB de RAM e 0,1 CPU**; dorme após 15 min sem tráfego e leva cerca de 1 min para acordar; **750 horas/mês por workspace** (um serviço ligado o mês todo consome ~720); disco efêmero. **Sem meio de pagamento cadastrado, exceder horas ou banda SUSPENDE os serviços até o mês seguinte e exceder minutos de build bloqueia novos builds; não há cobrança** (com cartão cadastrado, banda e build excedentes poderiam ser cobrados: por isso nunca cadastrar). Não está confirmado se o cadastro pode pedir verificação de telefone: ver na hora.
4. **Frontend (Cloudflare Pages Free):** 500 builds/mês, 1 build simultâneo, 20 mil arquivos por site, 25 MiB por arquivo; requisições a arquivos estáticos sem limite. Para o QueueCare, usar APENAS hospedagem estática: não depender de Pages Functions nem Workers (as requisições delas contam na franquia do Workers Free, 100 mil por dia); se um dia forem introduzidos, observar os limites do Workers Free.
5. **Descartados (com o motivo):** **Koyeb** exige cartão para verificação (pré-autorização de US$ 29 cancelada na hora) mesmo na instância gratuita: só com decisão EXPLÍCITA do Matheus; **Hugging Face Spaces** (Docker com compute exige plano PRO em conta pessoal; Space público expõe o código); **Back4app** (256 MB e URL gratuita que expira em 60 min por deploy); **Railway** (trial de US$ 5 e depois US$ 1/mês: não é hospedagem gratuita estável); **Northflank** (a própria documentação exige meio de pagamento); **Zeabur** (sem cartão, mas limites de execução não confirmados: só investigação futura); **AWS, Cloud Run** (conta de faturamento), **Fly.io, Oracle Always Free** (cartão), **Vercel Hobby** (uso não comercial). **Não há backend reserva aprovado**: não declarar um sem limites oficiais confirmados.
6. **Se o Render exigir cartão ou o Spring Boot não couber em 512 MB: PARAR a publicação do backend.** Não trocar de provedor sozinho; nova pesquisa e decisão do Matheus. A alternativa honesta de demonstração é publicar só o frontend com dados simulados (aviso visível de que a API não está conectada) e/ou gravar um vídeo do sistema completo rodando localmente.
7. **Compatibilidade com o código (auditada em 14/11/2026):** a migration V1 já cria `btree_gist`; todos os bloqueios são TRANSACIONAIS (`pg_advisory_xact_lock`, `set_config('lock_timeout', ..., true)` e bloqueio de linha `FOR NO KEY UPDATE`), então funcionam com o PgBouncer em modo transação do Neon. Não há `pg_advisory_lock` de sessão nem `SET` de sessão. **Regra a manter:** nunca introduzir bloqueio nem configuração de SESSÃO (quebraria no pooler).
8. **Conexões:** a aplicação usa a URL com pooler; o Flyway usa a URL DIRETA (sem pooler). Pool Hikari pequeno (máx. 3).
9. **Segredos:** só nos painéis dos provedores (JWT_SECRET, DB_URL, DB_USER, DB_PASSWORD) e em GitHub Secrets (tokens de deploy); nunca no repositório. O JWT_SECRET é fixo (gerar um novo a cada deploy invalidaria os tokens).
10. **CORS:** só a origem do frontend publicado (`queuecare.cors.allowed-origins`), nunca `*`; HTTPS nos três serviços.
11. **Memória e CPU, MEDIDOS (14/11/2026) com a imagem real em contêiner limitado a 512 MB e 0,1 CPU:** com `-Xms64m -Xmx256m -XX:MaxMetaspaceSize=128m -XX:+UseSerialGC` a API sobe em ~187 s e usa ~310 MB; **adicionando `-XX:TieredStopAtLevel=1 -Xss512k` (só o compilador leve) sobe em ~95 s e usa ~250 MB** (adotado no Dockerfile, com `-XX:+ExitOnOutOfMemoryError`). Sem falta de memória. Cadastro e login levam 3 a 5 s (BCrypt na CPU limitada). Cold start esperado na primeira visita: de um a dois minutos. As medidas valem para o Docker local; reconferir no Render.
12. **Saúde:** só `/actuator/health` exposto; a vivacidade não deve depender do banco.
13. **Cold start:** a primeira requisição após inatividade é lenta (backend e banco acordam); a tela avisa com mensagem amigável e a demonstração começa "acordando" o serviço.
14. **Nunca ser cobrado:** nenhum cartão; nenhuma instância ou upgrade pago; conferir o painel de uso todo mês; backup lógico local do banco; se um limite for atingido, o serviço suspende e a demonstração fica indisponível até o mês seguinte (aceito).
15. **Desligamento:** excluir o serviço do Render; exportar e excluir o projeto Neon; excluir o projeto Cloudflare Pages; **revogar os tokens E apagar os GitHub Secrets de deploy** (são ações diferentes); rotacionar segredos se houver suspeita de exposição; conferir que nada ficou ativo.

## Etapas (esforço)
| # | Etapa | Esforço | Quem |
|---|---|---|---|
| 1 | Criar e conferir as contas gratuitas (Neon, Render, Cloudflare) SEM cartão; parar se pedirem | Médio | **Matheus** |
| 2 | Banco Neon: criar, rodar o Flyway com a URL direta | Médio | Claude com o Matheus |
| 3 | Perfil prod, Dockerfile, `render.yaml`, CI da imagem e roteiro ([docs/publicacao.md](../publicacao.md)) | Médio | **feito** |
| 4 | Medir memória e tempo de inicialização em 512 MB e 0,1 CPU | Alto | **feito no Docker** (item 11); reconferir no Render |
| 5 | Publicar backend e frontend, CORS, aviso de cold start | Médio | Claude com o Matheus |
| 6 | Deploy por GitHub Actions com segredos | Médio | Claude |
| 7 | Testar jornadas e retomada após suspensão (junto com o E2E) | Alto | juntos |

## Consequências
- Não é disponibilidade de produção: é uma vitrine que acorda sob demanda e pode ficar fora do ar ao atingir um limite.
- Os planos gratuitos de terceiros podem mudar; este ADR precisa ser reconferido na hora de publicar.
