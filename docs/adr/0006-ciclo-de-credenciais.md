# ADR 0006: ciclo de credenciais (troca e redefinição de senha)

- **Status:** Accepted
- **Data:** 2026-11-13
- **Quem decidiu:** o Matheus delegou; desenho de Claude revisado pelo ChatGPT (revisor independente), por mexer em autenticação.

## Contexto
O ADR 0005 criou contas com senha inicial definida por outra pessoa e a restrição `must_change_password`, mas ainda não havia como trocar a senha nem como o ADMIN ajudar quem perdeu o acesso. Também faltava invalidar sessões quando a senha muda: com JWT de 15 minutos, um token roubado continuaria valendo depois da troca.

## Decisão
1. **Versão das credenciais** (`users.token_version`, V4): o token carrega a claim `ver`; o conversor compara com a versão ATUAL do banco a cada requisição e responde 401 se for diferente (claim ausente vale 0, então os tokens antigos continuam valendo até a primeira troca). Trocar ou redefinir senha incrementa a versão: todo token anterior, inclusive um roubado, morre na hora. É exato (um inteiro), sem depender de relógio nem de arredondamento de segundos.
2. **`POST /api/auth/change-password`** (exige login; é, com `GET /api/me`, a única rota aberta a conta com troca pendente): corpo `currentPassword` e `newPassword`. Tudo numa transação com a linha do usuário bloqueada (`SELECT ... FOR UPDATE`, espera limitada por `lock_timeout`, 409 `RESOURCE_BUSY` se estourar): confere a senha atual, aplica a política da nova senha, recusa nova igual à atual, grava o novo hash, zera `must_change_password`, sobe a versão, audita `PASSWORD_CHANGED` e devolve um token NOVO. Não altera perfil nem e-mail (campos como `role` e `email` no corpo são ignorados). Duas trocas simultâneas: a segunda já não confere a senha atual.
3. **Senha atual com limite de tentativas por usuário:** a tentativa é reservada de forma atômica ANTES de comparar (mesmo limitador do bloqueio de login: padrão 5 por 15 minutos, `queuecare.rate-limit.password-change-attempts`); a seguinte recebe 429 mesmo com a senha certa; sucesso zera. Quem tem só um token roubado não adivinha a senha atual. Senha atual errada é 400 `INVALID_CURRENT_PASSWORD` (não 401: a pessoa está autenticada).
4. **`POST /api/admin/users/{id}/reset-password`** (só ADMIN): define uma senha inicial nova (mesma política), obriga a troca no próximo acesso, invalida os tokens do usuário e audita `PASSWORD_RESET` (ator ADMIN, alvo o usuário). Usuário inexistente: 404. O ADMIN **não** redefine a PRÓPRIA senha por aqui (409 `USE_PASSWORD_CHANGE`; a troca exige a senha atual). Funciona para qualquer perfil, inclusive outro ADMIN (é o caminho de recuperação do ADR 0005), e **não reativa** conta inativa. A senha nunca volta em resposta (204).
5. **Primeiro ADMIN:** o bootstrap agora cria o ADMIN com `must_change_password = true` (política prometida no ADR 0005): a senha do comando só serve para o primeiro login e a troca é obrigatória; depois dela a senha do bootstrap deixa de valer.
6. Auditoria: `audit_log` ganhou as ações `PASSWORD_CHANGED` e `PASSWORD_RESET` (a tabela segue somente de acréscimo e sem segredos).

## Alternativas descartadas
- **Invalidar por data da troca (`password_changed_at` e `iat`):** o `iat` tem precisão de segundo; um token emitido no mesmo segundo da troca sobreviveria, e o token novo seria rejeitado se a regra fosse estrita. A versão inteira não tem essa janela.
- **Lista de tokens revogados:** estado extra para algo que o próprio usuário no banco já resolve com uma coluna.
- **ADMIN redefinir a própria senha pela rota de redefinição:** contornaria a exigência da senha atual.
- **Devolver a senha gerada na redefinição:** o segredo trafegaria em resposta; o ADMIN informa a senha inicial e ela obriga a troca.

## Consequências e limitações conhecidas
- Todos os dispositivos da pessoa perdem a sessão quando a senha muda (intencional).
- Ainda não existe recuperação de senha por e-mail ("esqueci minha senha"); a recuperação é pelo ADMIN. Sem outro ADMIN, só pelo banco (ADR 0005).
- A redefinição pelo ADMIN não tem limite próprio por hora; o ADMIN é conta confiável e todas as ações são auditadas.
