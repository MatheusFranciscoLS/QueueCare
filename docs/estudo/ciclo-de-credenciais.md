# Estudo: ciclo de credenciais (PR 18)

## Em uma frase
Trocar a senha (ou o ADMIN redefini-la) derruba na hora todas as sessões antigas, e quem tem só um token roubado não consegue adivinhar a senha atual.

## O que existe
- **`POST /api/auth/change-password`:** senha atual + nova (política de sempre, diferente da atual). Zera a pendência de troca, sobe a versão das credenciais, audita e devolve token novo.
- **Versão das credenciais (`token_version` + claim `ver`):** o token só vale se a versão dele for a do banco. Trocar/redefinir senha incrementa: tokens anteriores morrem.
- **`POST /api/admin/users/{id}/reset-password`:** só ADMIN; define senha inicial nova, obriga troca, invalida tokens, audita; não serve para a própria senha nem reativa conta inativa.
- **Primeiro ADMIN:** nasce com troca obrigatória.
- **Limite:** 5 tentativas de senha atual por usuário em 15 minutos (429 depois, mesmo com a senha certa).

## Como ler o código
1. `V4__ciclo_de_credenciais.sql`.
2. `PasswordService`: troca e redefinição (bloqueio da linha, limite, auditoria).
3. `ActiveUserJwtConverter`: a comparação da versão.
4. `AuthController.changePassword` e `AdminUserController`.

## Provas por teste
- Troca: token usado na troca morre e o novo opera; login só com a senha nova; perfil e e-mail intactos mesmo com `role`/`email` no corpo.
- Falhas: senha atual errada (400, sem mudar nada, 429 ao estourar, sucesso zera); nova fraca, longa demais ou igual à atual.
- Versão do token: ausente conta como 0; menor, maior, texto e nulo dão 401.
- Concorrência: 8 trocas simultâneas com a mesma senha atual dão exatamente 1 vitória e uma só senha nova válida; troca e redefinição simultâneas terminam coerentes, sem 500.
- Linha bloqueada por outra transação: 409 rápido e o limite de espera não vaza para o pool.
- Admin: só ADMIN; inexistente 404; si mesmo 409; outro ADMIN permitido; inativo continua inativo.
- Primeiro ADMIN: entra, só troca a senha, troca e então opera.
- Mutação: sem a comparação da versão e sem o bloqueio da linha os testes falham.

## Três perguntas de entrevista
1. **Como invalidar um JWT sem lista de revogação?** Guardando uma versão no usuário e na claim do token; mudar a versão no banco invalida todos os anteriores.
2. **Por que não comparar o `iat` com a data da troca?** O `iat` tem precisão de segundo e há uma janela em que um token antigo sobrevive ou o novo é rejeitado.
3. **Por que limitar tentativas na troca de senha se o usuário já está logado?** Porque um token roubado prova a identidade, não a senha; sem limite, quem o roubou adivinharia a senha atual.
