# Estudo: base do front-end (PR 20)

## Em uma frase
A interface já entra, cadastra, obriga a trocar a senha provisória e sai, com o token só em memória e todos os erros da API traduzidos em mensagens claras.

## O que existe
- Telas: entrar, cadastro de cliente, trocar senha (obrigatória ou voluntária) e início por perfil (honesto sobre o que ainda vem).
- `api/client.ts`: o único ponto que fala com a API.
- `auth/session.tsx`: sessão em memória, saída automática antes do vencimento, 401 encerra a sessão.
- `auth/guards.tsx`: rotas protegidas e o desvio para a troca de senha pendente.
- Tema claro/escuro e acessibilidade básica.

## Como ler o código
1. `src/api/client.ts` e `src/api/messages.ts`.
2. `src/auth/session.tsx` e `src/auth/guards.tsx`.
3. `src/pages/*` (cada tela é pequena).
4. `src/test/*`: os testes mostram o comportamento esperado.

## Provas por teste (48)
- Cliente HTTP: token anexado, sem cookies, 204, problem+json, corpo não JSON, falha de rede.
- Login: sucesso, credenciais erradas, bloqueio temporário com o tempo de espera, botão desabilitado sem dados.
- Segurança: token nunca em localStorage, sessionStorage ou cookie.
- Troca obrigatória: desvio para a tela de troca, token novo usado, validações antes de chamar a API, senha atual errada no campo certo.
- Cadastro: validações, entrada já logada, e-mail repetido no campo.
- Sessão: sair, vencimento do token com aviso, links por perfil.
- Corridas: "Sair" enquanto `/api/me` está pendente não reabre a sessão; 401 atrasado da sessão antiga não derruba a nova; login lento não sobrepõe o mais novo (mutação: sem a geração, os testes falham).
- Guarda: nenhuma navegação (início, cadastro, entrar, voltar, avançar) tira a pessoa da troca pendente; rota desconhecida não vaza dados.
- Entradas e erros: destino pós-login só interno, `AbortError` relançado, 409 genérico sem `detail`, duplo envio ignorado, recarregar não restaura a sessão, erro do campo some ao corrigir e o foco vai ao primeiro inválido.

## Três perguntas de entrevista
1. **Por que o token não fica em localStorage?** Qualquer script injetado o lê; em memória, o roubo exige estar na página naquele momento.
2. **Por que o front repete a política de senha se a API já valida?** Para avisar cedo e evitar viagens inúteis; a decisão continua sendo da API.
3. **Como o front reage a um token que deixou de valer em outro aparelho?** Todo 401 de chamada autenticada encerra a sessão e leva à entrada.

## Limitações e o que falta
- Sem telas de negócio ainda; sem teste ponta a ponta com navegador real (Playwright entra depois).
