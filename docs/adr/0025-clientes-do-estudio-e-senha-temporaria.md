# ADR 0025: clientes do estúdio e senha temporária para o tutor

- **Status:** Accepted (implementado em 21/11/2026, segundo item do plano de prontidão para uso real)
- **Data:** 21/11/2026
- **Esforço:** MÉDIO (duas rotas, uma migração, uma tela, 9 testes de integração e 11 de tela)

## Contexto: a análise crítica
Duas dores reais de um estúdio no balcão:
1. **"A Marina ligou, esqueceu a senha."** Hoje só o ADMIN da plataforma redefine senha. Um tutor trancado para fora depende do dono do sistema, e a alternativa óbvia (recuperação por e-mail) exige um serviço de envio: custo, domínio, entregabilidade. O ADR 0013 fixa **custo zero**.
2. **"Quem é esse tutor, quantas vezes veio, o que ele tem?"** A Agenda mostra um dia; para achar um cliente por nome, telefone ou pet não há tela.

## Decisão
1. **Tela "Clientes"** (dono e atendente): lista os tutores que o estúdio **já atendeu** (ao menos um agendamento nele, de qualquer estado), do agendamento mais recente para o mais antigo, com nome, WhatsApp, atendimentos concluídos, faltas, data do último agendamento e nomes dos pets. A busca ignora maiúsculas e acentos, casa nome, nome de pet e telefone (a partir de 3 dígitos), devolve no máximo 30 e roda sozinha 300 ms depois da última tecla (Enter busca na hora).
2. **Senha temporária gerada pelo dono**, sem e-mail. `POST /api/provider/customers/{id}/reset-password` devolve uma senha **aleatória de 12 caracteres** (alfabeto sem 0/O, 1/l/I) **uma única vez**, com `Cache-Control: no-store`. O tutor **precisa trocar no primeiro acesso**, e os tokens que ele já tinha deixam de valer (a versão das credenciais sobe). O dono copia ou manda pelo WhatsApp (o app só prepara o texto; a senha vai **sozinha numa linha**).
3. **Quem pode:** só o `PROVIDER` (dono). O atendente busca, mas não gera senha: acessar a conta de um cliente é um poder maior que o de atender.

## Segurança: o que protege e o que fica de risco
O risco central é o **dono virar dono da conta do tutor**: ele conhece a senha temporária e pode entrar antes do tutor. O tutor tem uma conta global na plataforma (pets e agendamentos de outros estúdios). Mitigações adotadas:
- **Só tutor que o estúdio já atendeu.** Tutor inexistente, de outro estúdio, nunca atendido, inativo ou que não é tutor (dono, atendente, admin) tem a **mesma resposta 404**, sem pista. O limite abaixo impede varrer ids.
- **Limite de 10 senhas por hora por estúdio**, contado **antes** de qualquer conferência (429 com `Retry-After`). Documentado no contrato OpenAPI.
- **Auditoria** (`CUSTOMER_PASSWORD_RESET_BY_STUDIO`, ator = dono, alvo = tutor), gravada na mesma transação. A tabela de auditoria **não tem coluna de texto livre**: a senha não tem onde ser gravada, nem por engano.
- **A senha nunca é guardada em claro**, aparece uma vez e some da tela ao concluir.
- **Troca obrigatória**: a senha que o dono conhece vale só até o tutor entrar uma vez.

**Risco residual, aceito e declarado:** entre o dono gerar e o tutor entrar, o dono poderia entrar como o tutor. A auditoria dá rastro, mas não impede. Para tutor que perdeu o acesso e não tem como receber um link, não há solução sem e-mail que elimine isso. **Evolução recomendada** quando houver orçamento para e-mail transacional: link de uso único enviado ao tutor, tirando a senha das mãos do estúdio.

## Fora de escopo
- E-mail e SMS (custo), recuperação sem intervenção do estúdio, exportar a lista de clientes, edição de cadastro do tutor pelo estúdio, histórico detalhado por cliente (a ficha do pet e as visitas anteriores já existem na Agenda).

## Consequências
- O tutor trancado para fora volta em dois toques do dono, sem depender do administrador da plataforma.
- O balcão acha um cliente em segundos, pelo que a pessoa lembra (nome, telefone ou o nome do pet).
- Um índice novo (`appointments (provider_id, customer_id)`) sustenta a busca e a checagem "o estúdio já atendeu este tutor?".
- A busca lê todos os tutores do estúdio e filtra em memória: serve a estúdios de milhares de clientes; passar de dezenas de milhares pede paginação e filtro no banco.
