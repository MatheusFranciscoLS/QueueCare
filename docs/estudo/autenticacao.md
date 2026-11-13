# Estudo: autenticação e autorização (PR 11)

## Em linguagem simples
Autenticação responde **"quem é você?"**; autorização responde **"o que você pode fazer?"**.

1. A pessoa se **cadastra** (`POST /api/auth/register`): sempre vira **cliente**; a senha é guardada só como **hash BCrypt** (uma "impressão digital" da senha que não dá para desfazer).
2. Faz **login** (`POST /api/auth/login`) e recebe um **token JWT** de 15 minutos: um texto assinado pelo servidor que diz "este é o usuário X". O servidor assina com um **segredo** que só ele conhece (`JWT_SECRET`); quem não tem o segredo não consegue forjar um token.
3. Em cada chamada seguinte, o front envia `Authorization: Bearer <token>`. O Spring Security confere a assinatura, o prazo, o emissor e a audiência; depois a aplicação **consulta o usuário no banco** e usa o **perfil do banco** (cliente, atendente ou prestador). Se o usuário foi desativado, o acesso cai na hora.
4. Cada rota decide quem pode entrar (`@PreAuthorize`): **401** = "não sei quem é você"; **403** = "sei quem é, mas você não pode".

```
cliente --register--> API  (cria CUSTOMER, hash BCrypt)
cliente --login-----> API  (confere senha, emite JWT 15 min)
cliente --GET /api/me + Bearer token--> API (valida token, consulta usuário no banco)
```

## Detalhes de segurança que importam
- O **perfil nunca vem do cliente**: mesmo enviando `"role":"PROVIDER"` no cadastro, vira CUSTOMER; mesmo com um token dizendo PROVIDER, vale o perfil do banco.
- **Login não revela se o e-mail existe**: a mesma resposta e o mesmo esforço de cálculo para e-mail inexistente e senha errada.
- Senhas: mínimo de 10 caracteres; máximo de 72 bytes (limite do BCrypt), recusadas em vez de cortadas em silêncio.
- Token recusado se: expirado, assinatura de outra chave, adulterado, **sem assinatura (`alg: none`)**, emissor ou audiência errados, sem `exp`, `sub` que não é UUID.
- Sem sessão e sem cookie: por isso não precisa de proteção CSRF; CORS só aceita a origem do front configurada.
- Detalhe do framework: o Spring preenche `iat` sozinho quando o token não o traz; o que protege é exigir e validar o `exp`. Além disso, `iat` no futuro (além de 30 s) ou depois de `exp` é recusado, e dois cabeçalhos `Authorization` na mesma requisição são recusados em vez de escolher um em silêncio.

## O que os testes provam (Spring completo + filtros reais + PostgreSQL real)
- **Cadastro (9 testes):** cria cliente sem expor hash; ignora `role`; normaliza e-mail; duplicado dá 409 (inclusive 4 cadastros simultâneos: só um vence); dados inválidos; senha de 73 bytes recusada e de 72 aceita; JSON quebrado.
- **Login (6 testes):** token com os claims certos e 15 minutos; senha errada e e-mail inexistente dão **exatamente a mesma resposta**; inativo recusado; senha gigante sem erro 500.
- **Token e acesso (16 testes):** os casos de recusa acima, usuário desativado depois de logado, usuário inexistente, cada perfil só nas rotas permitidas (rotas de prova só de teste), rotas públicas, padrão "tudo fechado", CORS, sem cookie.
- **Configuração (4 testes):** segredo ausente, não-Base64 ou com menos de 32 bytes impede a aplicação de subir.

## Limite de tentativas (força bruta)
- Login e cadastro têm limite por minuto e por endereço; horários livres, por usuário. Excedeu: **429** com `Retry-After`.
- **5 falhas de login** do mesmo e-mail **no mesmo endereço** em 15 minutos bloqueiam esse par (mesmo com a senha certa). Toda falha conta, inclusive e-mail inexistente, então o bloqueio **não revela quais contas existem**. Em outro endereço a dona da conta entra normalmente.
- Testes: `RateLimiterTest` (relógio controlado, memória limitada), `RateLimitEndpointsIntegrationTest` e `LoginLockoutIntegrationTest`.

## Onde está
- `backend/src/main/java/com/queuecare/security/`: `JwtProperties`, `JwtConfig`, `SecurityConfig`, `ActiveUserJwtConverter`
- `backend/src/main/java/com/queuecare/auth/`: `AuthService`, `AuthController`, `MeController`, DTOs, exceções e `ApiExceptionHandler`
- ADR: `docs/adr/0003-autenticacao.md` (aceito)

## 3 perguntas de entrevista (responda com as suas palavras)
1. Por que o perfil do usuário vem do banco e não do que está escrito no token?
   *Pista: desativar um usuário e a diferença entre confiar em quem assinou e em dados que podem estar velhos.*
2. Por que o login devolve a mesma resposta (e gasta o mesmo tempo) para e-mail inexistente e para senha errada?
   *Pista: enumeração de usuários.*
3. Por que a API pode dispensar proteção CSRF e por que o CORS ainda importa?
   *Pista: token enviado no cabeçalho, sem cookie de autenticação.*

## Limitações e o que ainda falta
- O limite de tentativas é em memória e por instância (ver ADR 0003); sem refresh token (login a cada 15 min); rotação do segredo é manual.
- PROVIDER e ATTENDANT ainda não têm cadastro; só CUSTOMER se cadastra.
- Ainda não há endpoints de agendar e cancelar (próximo PR): o `customerId` virá do token.
- `JWT_SECRET` é obrigatório para rodar a aplicação (veja `.env.example` e o README).
