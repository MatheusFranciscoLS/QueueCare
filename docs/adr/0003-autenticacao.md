# ADR 0003: autenticação (JWT ou sessão)

- **Status:** Accepted
- **Data:** 2026-11-13
- **Quem decidiu:** o Matheus delegou a escolha; recomendada por Claude e revisada pelo ChatGPT (revisor independente), que acrescentou os requisitos de segurança abaixo.

## Contexto
React (SPA) conversando com uma API Spring Boot, três perfis (PROVIDER, ATTENDANT, CUSTOMER) e autorização validada na API (R4 em `docs/regras.md`). Deploy futuro em AWS, front e API possivelmente em endereços diferentes. O projeto também serve para mostrar Spring Security aplicado, e vagas de Java júnior citam JWT com frequência.

## Alternativas consideradas
1. **Sessão com cookie (HttpOnly, SameSite) e estado no servidor.** Mais simples e mais segura contra XSS; exige tratar CSRF e funciona melhor quando front e API estão no mesmo site. Revogar é fácil.
2. **JWT stateless (access token curto) enviado em `Authorization: Bearer`.** Padrão comum em API REST + SPA, bom para aprender Spring Security. Revogar é mais difícil; guardar o token no navegador exige cuidado com XSS.
3. **JWT + refresh token (cookie HttpOnly) com rotação.** Mais completo e mais trabalho; evolução depois do MVP.

## Decisão
**Alternativa 2**, implementada com **Spring Security Resource Server + Nimbus** (sem filtro próprio):
- **Token:** JWT **HS256** (só esse algoritmo), validade de **15 minutos**, **sem refresh token** no MVP. Claims: `iss`, `aud`, `sub` (UUID do usuário), `role`, `iat`, `exp`. Nada de dados pessoais ou senha no token.
- **Validação:** assinatura, **emissor** e **audiência** (`queuecare-api`), `exp` obrigatório e não expirado (tolerância de relógio de **30 s**), `iat` coerente (não no futuro além da tolerância e não depois de `exp`), `sub` precisa ser UUID. `alg: none` e assinatura de outra chave são recusados. Mais de um cabeçalho `Authorization` é recusado (401), esquema que não seja Bearer e token na query string não autenticam.
- **Segredo:** variável de ambiente `JWT_SECRET`, em **Base64**, com **pelo menos 32 bytes (256 bits)** depois de decodificar (gerar com `openssl rand -base64 32`). **Sem valor padrão**: a aplicação não sobe sem ele. Os testes usam um segredo exclusivo de teste.
- **Perfil e usuário ativo vêm do BANCO**, não do token: a cada requisição o usuário é consultado; usuário inexistente ou inativo recebe 401 na hora (desativar vale sem esperar o token expirar). O `role` do token é só informativo.
- **Senha:** BCrypt (custo 12 por padrão, calibrável), mínimo de **10 caracteres** e máximo de **72 bytes em UTF-8** (limite do BCrypt; recusada, nunca truncada).
- **Cadastro público** (`POST /api/auth/register`) **sempre cria CUSTOMER**; um `role` enviado pelo cliente é ignorado. PROVIDER e ATTENDANT serão criados por outro caminho (seed ou administração).
- **Login** (`POST /api/auth/login`): mensagem única "credenciais inválidas" (401) para e-mail inexistente, senha errada ou usuário inativo, e BCrypt calculado sempre (contra um hash fictício pré-calculado quando a conta não existe) para não revelar contas pelo tempo de resposta.
- **E-mail:** sem espaços externos e em minúsculas; o índice único `lower(email)` do banco é a garantia final contra duplicidade concorrente. Cadastro duplicado responde **409** (revela que o e-mail já existe: trade-off de produto aceito no MVP).
- **API stateless:** sem sessão e sem cookie de autenticação; por isso **CSRF desabilitado**. CORS restrito às origens configuradas (padrão `http://localhost:5173`). Tudo exige token, exceto cadastro, login e `/actuator/health`. 401 = não autenticado; 403 = autenticado sem permissão (`@PreAuthorize`).
- **DTOs** de entrada e saída; entidades JPA nunca são expostas. A identidade usada nas operações vem **sempre do principal autenticado**, nunca de um UUID enviado no corpo da requisição.
- **Limite de requisições e bloqueio de força bruta:** `POST /api/auth/login` e `POST /api/auth/register` por endereço e `GET /api/providers/{id}/slots` por usuário autenticado: excedeu, 429 com `Retry-After` e corpo genérico. Além disso, **5 falhas de login para o par e-mail + endereço em 15 minutos** bloqueiam temporariamente esse par (429, mesmo com a senha certa). TODA falha conta (inexistente, inativo, senha errada), então o bloqueio não revela contas; a chave inclui o endereço, então um atacante não tranca a conta do dono em outro endereço; login com sucesso zera a contagem. Atrás de proxy, configurar `server.forward-headers-strategy` para o endereço ser o do cliente real.
- Nunca registrar em log senha, hash, token ou cabeçalho `Authorization`.

## Consequências
- Aprende-se o núcleo do Spring Security (cadeia de filtros, Resource Server, `PasswordEncoder`, `@PreAuthorize`).
- Uma consulta ao banco por requisição autenticada (custo aceito pelo MVP em troca de revogação imediata).
- **Limitações conhecidas:** (1) se o segredo vazar, é preciso trocá-lo e todos os tokens antigos deixam de valer (rotação com `kid` fica para depois); (2) o **limite de tentativas** é em memória e por instância (valores por minuto: login 20 e cadastro 10 por endereço, horários livres 120 por usuário; padrões configuráveis em `queuecare.rate-limit.*`); com mais de uma instância os limites valem por instância e o ideal é um limitador compartilhado ou o do proxy; (3) sem refresh token, a pessoa refaz o login a cada 15 minutos; (4) PROVIDER e ATTENDANT ainda não têm fluxo de criação.
- Testes (`docs/estudo/autenticacao.md`): cadastro, login, expiração, adulteração, `alg none`, emissor, audiência, `sub`, usuário desativado, perfis e CORS, com os filtros reais do Spring Security e PostgreSQL real.
