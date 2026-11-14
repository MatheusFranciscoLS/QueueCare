# ADR 0007: ativar e desativar usuários

- **Status:** Accepted
- **Data:** 2026-11-13
- **Quem decidiu:** o Matheus delegou; desenho de Claude revisado pelo ChatGPT (revisor independente), por mexer em acesso e em regras que precisam valer sob concorrência.

## Contexto
Faltava encerrar o acesso de uma conta (funcionário que saiu, conta comprometida) e reativá-la, sem apagar histórico. Também era preciso garantir que o sistema nunca fique sem administrador ativo, mesmo com dois administradores agindo ao mesmo tempo.

## Decisão
1. **ADMIN** ativa e desativa qualquer conta: `POST /api/admin/users/{id}/deactivate` e `.../activate` (204). Não desativa a PRÓPRIA conta (409 `CANNOT_DEACTIVATE_SELF`), nunca deixa o sistema sem ADMIN ativo (409 `LAST_ACTIVE_ADMIN`) e não há exclusão de usuário pela API (o banco também recusa apagar usuário com histórico, FKs RESTRICT).
2. **Linha de controle** (`admin_control`, uma única linha, V5): toda ativação/desativação trava essa linha ANTES de decidir, reconfere que o ator ainda é um ADMIN ativo e só então trava o usuário alvo. Dois ADMIN que se desativam ao mesmo tempo são atendidos um de cada vez: o segundo já não é ativo e é recusado. Ordem de bloqueios única e fixa (controle, depois usuário alvo), com espera limitada (`RowLocks`, `user-lock-timeout`, 409 `RESOURCE_BUSY`). A **redefinição de senha pelo ADMIN também passa pela linha de controle** e reconfere o ator sob a trava (um ADMIN desativado depois de autenticar não redefine mais senhas).
   O bloqueio do usuário é `FOR NO KEY UPDATE`: não conflita com o `FOR KEY SHARE` que a gravação da auditoria (FKs para ator e alvo) toma nas mesmas linhas, então não há ciclo "A trava B e precisa de A / B trava A e precisa de B" por causa da auditoria. Isso é fixado por teste (`RowLocksIntegrationTest`: com `FOR UPDATE` a auditoria ficaria esperando e o teste falha) e por uma corrida com auditoria artificialmente lenta.
3. **Desativar e reativar sobem a versão das credenciais** (ADR 0006): um token emitido antes da desativação não volta a valer quando a conta é reativada. Repetir a operação (já ativa ou já inativa) é 204 sem alterar nada e sem nova auditoria.
4. **O PROVIDER desativa só os PRÓPRIOS atendentes:** `POST /api/provider/staff/{userId}/deactivate` (204) desativa a conta E desfaz o vínculo na mesma transação, com auditoria. Atendente de outro prestador, usuário que não é atendente e id inexistente têm a MESMA resposta (404). A reativação é só do ADMIN (e a pessoa precisaria ser vinculada de novo).
5. **Prestador desativado some:** com o dono (usuário PROVIDER) inativo, o negócio não aparece para clientes (horários livres e reserva respondem "prestador não encontrado") e a equipe dele não opera (atendente só opera se o dono do prestador estiver ativo). Cancelamento de reservas já existentes pelo cliente continua possível.
6. Auditoria: `USER_DEACTIVATED` e `USER_ACTIVATED` (ator, alvo e perfil do alvo, mesma transação).

7. **Listagem de contas (só ADMIN):** `GET /api/admin/users?role&q&page&size` devolve id, nome, e-mail, perfil, ativo, `mustChangePassword` e data de criação; NUNCA senha, hash nem versão das credenciais. Filtro por perfil (inválido: 400) e busca no nome ou no e-mail sem diferenciar maiúsculas, com os curingas `%` e `_` digitados valendo como texto comum (escape no padrão LIKE); `q` até 100 caracteres; `size` de 1 a 50 (padrão 20), `page` a partir de 0; ordem estável (e-mail e depois id). **Paginação por deslocamento (offset):** a travessia é exata só enquanto o conjunto de contas NÃO muda; se alguém criar uma conta (ou ela sair do filtro) entre duas páginas, um item pode repetir ou ser pulado. Contrato: a tela volta à página 0 quando a busca ou o filtro mudam e depois de criar uma conta (isso muda quem está na lista); ativar, desativar e redefinir senha NÃO mudam quem está na lista nem a ordem, então a página atual é recarregada. A tela não promete travessia consistente durante alterações simultâneas; paginação por cursor (e-mail, id) fica para quando o volume exigir. É leitura: não grava auditoria. O ADMIN continua sem acesso a agendas e dados de negócio; a lista de contas é o que ele precisa para administrar.

## Alternativas descartadas
- **Só contar ADMIN ativos, sem trava:** dois ADMIN desativando um ao outro ao mesmo tempo passariam pela contagem e o sistema ficaria sem ADMIN.
- **Trava consultiva global (`pg_advisory_lock`):** funcionaria, mas a linha de controle é visível no esquema, testável e acompanha a transação.
- **Exclusão de usuário:** destruiria o histórico (agendamentos e auditoria); desativação lógica resolve.
- **Prestador reativa atendente:** exigiria revincular e decidir o que fazer com a conta; fica com o ADMIN.

## Consequências e limitações conhecidas
- Desativar um prestador não cancela as reservas futuras dele: elas continuam existindo e o cliente pode cancelar; tratar isso é decisão de produto para depois.
- A regra do último ADMIN é garantida pela combinação "ator ativo e diferente do alvo" sob a trava; a checagem explícita de contagem fica como defesa extra (e é inalcançável pela API hoje).
