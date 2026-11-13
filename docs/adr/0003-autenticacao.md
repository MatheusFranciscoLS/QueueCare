# ADR 0003: autenticação (JWT ou sessão)

- **Status:** Proposed (recomendação do Claude; o Matheus delegou e confirma ao revisar)
- **Data:** 2026-11-13

## Contexto
React (SPA) conversando com uma API Spring Boot, três perfis (PROVIDER, ATTENDANT, CUSTOMER) e autorização validada na API (R4 em `docs/regras.md`). Deploy futuro em AWS, front e API possivelmente em endereços diferentes. O projeto também serve para mostrar Spring Security aplicado, e vagas de Java júnior citam JWT com frequência.

## Alternativas consideradas
1. **Sessão com cookie (HttpOnly, SameSite) e estado no servidor.** Mais simples e mais segura contra XSS; exige tratar CSRF e funciona melhor quando front e API estão no mesmo site. Revogar é fácil.
2. **JWT stateless (access token curto, ex.: 15 a 30 min) enviado em `Authorization: Bearer`.** Padrão comum em API REST + SPA, bom para aprender Spring Security (filtro, `SecurityFilterChain`, claims com o perfil). Revogar é mais difícil; guardar o token no navegador exige cuidado com XSS.
3. **JWT + refresh token (cookie HttpOnly) com rotação.** Mais completo e mais trabalho; entra como evolução depois do MVP.

## Decisão (proposta)
Alternativa 2 no MVP: JWT stateless com access token curto, perfil como claim, senha com hash (BCrypt), autorização conferida em cada endpoint com Spring Security. Refresh token (alternativa 3) como evolução na fase 2 ou 3.
Cuidados: token só em memória no front (não em `localStorage`, se possível), validação de assinatura e expiração, segredo em variável de ambiente, nunca em log.

## Consequências
- Aprende-se o núcleo do Spring Security (filtro JWT, `UserDetailsService`, `PasswordEncoder`, `@PreAuthorize` ou regras por rota).
- Revogação imediata não existe no MVP (aceitável com expiração curta); registrar como limite conhecido.
- Testes: MockMvc com usuários de cada perfil (T5) e teste de token expirado/inválido.
