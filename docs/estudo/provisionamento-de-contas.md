# Estudo: provisionamento de contas (PR 17)

## Em uma frase
Ninguém vira prestador, atendente ou administrador por SQL manual: cada perfil nasce por um fluxo próprio, atômico, auditado e com senha inicial que precisa ser trocada.

## O que existe
- **ADMIN** (novo perfil): cria prestadores. Não vê agenda, cliente, serviço nem equipe de ninguém.
- **Bootstrap do primeiro ADMIN:** comando único (`queuecare.bootstrap-admin.enabled=true`) com `ADMIN_EMAIL` e `ADMIN_PASSWORD` do ambiente. Idempotente, serializado no banco, nunca ecoa e-mail ou senha, nunca mexe em ADMIN existente.
- **`POST /api/admin/providers`:** usuário PROVIDER + negócio + auditoria, tudo na mesma transação.
- **`POST /api/provider/staff/accounts`:** atendente NOVO + vínculo ao negócio de quem chamou + auditoria, numa transação. `role` e `providerId` do corpo são ignorados.
- **`must_change_password`:** conta criada por outra pessoa só consulta `GET /api/me` e (PR seguinte) troca a senha; o resto é 403 `PASSWORD_CHANGE_REQUIRED`, lido do banco a cada requisição.
- **`audit_log`:** somente de acréscimo (gatilho no banco), sem colunas para segredos, com alvo e perfil obrigatórios e conferidos pelo banco (FK composta).
- **Conflito de e-mail uniforme:** criar conta para outra pessoa e o e-mail já existir dá sempre o mesmo 409, qualquer que seja o perfil da conta existente; tentativas limitadas por usuário (30 por hora por padrão).

## Como ler o código
1. `V3__provisionamento_de_contas.sql`: perfil ADMIN, coluna da troca de senha, tabela e gatilho da auditoria.
2. `AccountProvisioner`: o único lugar que cria usuário.
3. `AdminBootstrapService` e `AdminBootstrapRunner`: o comando único.
4. `AdminProviderService` e `StaffService.createAttendantAccount`: as criações atômicas.
5. `ActiveUserJwtConverter` e `PasswordChangeRequiredFilter`: a restrição da conta pendente.
6. `SecurityConfig`: perfil exigido na rota, antes do corpo.

## Provas por teste
- Atomicidade: gatilho de teste faz a auditoria (ou o vínculo) falhar; usuário e negócio somem junto.
- Concorrência: 6 criações simultâneas do mesmo e-mail dão 1 criação e 5 conflitos; 8 bootstraps simultâneos dão 1 ADMIN.
- Escalada: `role`, `providerId` e `mustChangePassword` enviados no corpo não surtem efeito.
- Perfis: ADMIN é recusado em toda rota de negócio; os demais perfis, na criação de prestador.
- Troca pendente: todos os perfis, todas as rotas, variações de caminho de `/api/me`, estado lido do banco nos dois sentidos.
- Segredos: nada de senha ou hash em resposta, auditoria ou log do bootstrap.
- Idempotência real: com o ADMIN criado, rodar o comando de novo sem as variáveis (ou com lixo) termina em sucesso sem mudar nada.
- Formato de e-mail: bootstrap e criação de prestador aceitam e recusam exatamente os mesmos 19 endereços de teste.
- Auditoria: sem alvo, sem perfil ou com perfil que não confere é recusado pelo PostgreSQL.
- Enumeração: o corpo do conflito é idêntico para sete e-mails de perfis, estados e caixas diferentes; o limite por hora responde 429 com `Retry-After` de mais de um minuto.
- Mutação: sem o filtro e sem o bloqueio consultivo os testes correspondentes falham.

## Três perguntas de entrevista
1. **Por que o ADMIN não pode ler agendas?** Porque a conta mais poderosa é a mais atacada; sem acesso a dados de cliente, o estrago de uma conta ADMIN comprometida é menor.
2. **Como garantir que dois comandos de bootstrap simultâneos não criem dois administradores?** Um bloqueio consultivo da transação no PostgreSQL serializa; o segundo vê o ADMIN já criado e não faz nada.
3. **Por que a restrição de troca de senha lê o banco a cada requisição, e não o token?** Porque o token vive 15 minutos; ler do banco faz a restrição (e a sua remoção) valer na hora, com o mesmo token.
