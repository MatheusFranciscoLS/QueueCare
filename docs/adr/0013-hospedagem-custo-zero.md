# ADR 0013: hospedagem com custo zero

- **Status:** Proposed (depende de o Matheus criar as contas; nada foi cadastrado, nenhum cartão usado)
- **Data:** 2026-11-14
- **Quem decidiu:** o Matheus exigiu CUSTO ZERO (sem servidor comprado, sem cartão se possível), o que descarta a AWS do roadmap (M5). Desenho de Claude e do ChatGPT.

## Contexto
O projeto é de portfólio: pode "dormir" quando ninguém usa, mas não pode gerar cobrança. O banco precisa ser PostgreSQL de verdade (extensão `btree_gist`, restrição de exclusão, bloqueios de linha).

## Decisão
1. **Arquitetura-alvo:** frontend estático na **Cloudflare Pages**; backend (JAR Spring Boot, Java 21) na **Koyeb** (instância gratuita, 512 MB, escala a zero após 1 h sem tráfego); banco no **Neon** (PostgreSQL gratuito, suspende o compute após 5 min, 1 GB). **Alternativas:** banco no Supabase; backend no Render (cobra excesso de banda, por isso é reserva). **Descartados:** AWS, Cloud Run (exige conta de faturamento), Fly.io, Railway, Oracle Always Free (exige cartão), Vercel Hobby (uso não comercial).
2. **Os limites e a exigência de cartão de cada plano MUDAM:** antes de criar cada conta, conferir a página oficial; se pedir cartão, parar e avisar o dono (nunca cadastrar meio de pagamento).
3. **Compatibilidade com o código (auditada em 14/11/2026):** a migration V1 já cria `btree_gist`; todos os bloqueios são TRANSACIONAIS (`pg_advisory_xact_lock`, `set_config('lock_timeout', ..., true)` e bloqueio de linha `FOR NO KEY UPDATE`), então funcionam com o PgBouncer em modo transação do Neon. Não há `pg_advisory_lock` de sessão nem `SET` de sessão. **Regra a manter:** nunca introduzir bloqueio nem configuração de SESSÃO (isso quebraria no pooler).
4. **Conexões:** a aplicação usa a URL com pooler; o Flyway deve usar a URL DIRETA (sem pooler). Pool Hikari pequeno (máx. 3).
5. **Segredos:** só no painel do provedor (JWT_SECRET, DB_URL, DB_USER, DB_PASSWORD) e em GitHub Secrets (tokens de deploy); nunca no repositório. O JWT_SECRET é fixo (não gerar um novo a cada deploy: invalidaria os tokens).
6. **CORS:** só a origem do frontend publicado (`queuecare.cors.allowed-origins`), nunca `*`; HTTPS nos três serviços.
7. **Memória do JVM:** `-Xmx` bem abaixo de 512 MB (ponto de partida: `-Xms64m -Xmx256m -XX:MaxMetaspaceSize=128m -XX:+UseSerialGC`), a medir de verdade antes de publicar.
8. **Saúde:** só `/actuator/health` exposto; a vivacidade não deve depender do banco.
9. **Cold start:** a primeira requisição depois de inatividade é lenta (backend e banco acordam); a tela deve avisar com mensagem amigável, e a demonstração começa "acordando" o serviço.
10. **Nunca ser cobrado:** não cadastrar cartão; não escolher instância paga; conferir o painel de uso mensalmente; manter backup lógico do banco; procedimento de desligamento documentado (excluir serviço, exportar e excluir o projeto Neon, excluir o projeto Cloudflare, revogar tokens).

## Etapas (esforço)
| # | Etapa | Esforço | Quem |
|---|---|---|---|
| 1 | Criar e conferir as contas gratuitas (Neon, Koyeb, Cloudflare) sem cartão | Médio | **Matheus** |
| 2 | Banco Neon: criar, rodar o Flyway com URL direta | Médio | Claude com o Matheus |
| 3 | Perfil prod, variáveis, SSL e pool | Médio | Claude |
| 4 | Medir memória e tempo de inicialização no limite de 512 MB | Alto | juntos |
| 5 | Publicar backend e frontend, CORS, aviso de cold start | Médio | Claude com o Matheus |
| 6 | Deploy por GitHub Actions com segredos | Médio | Claude |
| 7 | Testar jornadas e retomada após suspensão (junto com o E2E) | Alto | juntos |

## Consequências
- Não é disponibilidade de produção: é uma vitrine que acorda sob demanda.
- Os planos gratuitos de terceiros podem mudar; este ADR precisa ser reconferido na hora de publicar.
