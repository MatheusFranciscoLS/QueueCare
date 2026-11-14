# Estudo: ativar e desativar usuários (PR 19)

## Em uma frase
Quem sai perde o acesso na hora (sem apagar histórico), o sistema nunca fica sem administrador ativo, e o prestador só mexe nos próprios atendentes.

## O que existe
- **ADMIN:** desativa e reativa qualquer conta, menos a própria; nunca o último ADMIN ativo; sem exclusão.
- **Linha de controle `admin_control`:** a trava que faz dois ADMIN simultâneos serem atendidos um de cada vez.
- **Versão das credenciais:** desativar e reativar a incrementam; token antigo nunca ressuscita.
- **Prestador:** desativa só atendente seu (conta inativa + vínculo desfeito, numa transação). Alvo que não é atendente seu: 404 igual para todos.
- **Prestador desativado:** some para clientes e a equipe dele para de operar.
- **Auditoria:** `USER_DEACTIVATED` e `USER_ACTIVATED`.

## Como ler o código
1. `V5__administracao_de_usuarios.sql`.
2. `RowLocks`: bloqueios com espera limitada e a ordem fixa (controle, depois usuário).
3. `AdminUserService`: ativar/desativar com as regras.
4. `StaffService.deactivateAttendant`.
5. `ProviderRepository.findActiveById`, usado por horários livres, reserva e `StaffAccess`.

## Provas por teste
- Desativar/reativar: versão sobe, token antigo e login recusados, tokens não ressuscitam, auditoria, negócio some e volta.
- Idempotência: repetir não muda nem audita.
- Concorrência: dois ADMIN se desativando ao mesmo tempo, 15 rodadas: exatamente um vence e sobra um ADMIN ativo; prestador e ADMIN desativando o mesmo atendente: sem 500 e desativado uma única vez.
- Atomicidade: falha na auditoria desfaz a desativação e também o desvínculo.
- Permissões e escopo: só ADMIN (e prestador dono para atendente); alvo alheio ou inexistente tem resposta idêntica; troca de senha pendente bloqueia.
- Mutação: sem a trava da linha de controle, o teste dos dois ADMIN falha (os dois vencem).

## Três perguntas de entrevista
1. **Como garantir que sempre sobra um administrador ativo com dois administradores agindo juntos?** Uma linha de controle travada antes de decidir serializa as operações; sob a trava, o ator é reconferido.
2. **Por que desativação lógica e não exclusão?** O histórico (agendamentos, auditoria) precisa continuar existindo; FKs RESTRICT impedem apagar.
3. **Por que reativar também sobe a versão do token?** Senão, um token emitido antes da desativação voltaria a valer quando a conta fosse reativada.
