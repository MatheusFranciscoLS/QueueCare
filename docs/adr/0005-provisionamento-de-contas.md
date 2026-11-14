# ADR 0005: provisionamento de contas (ADMIN, prestador e atendente)

- **Status:** Accepted
- **Data:** 2026-11-13
- **Quem decidiu:** o Matheus delegou; desenho de Claude revisado pelo ChatGPT (revisor independente), por mexer em identidade, perfis e acesso.

## Contexto
Até o PR 16 só existia o cadastro público (sempre CUSTOMER). PROVIDER e ATTENDANT só nasciam por SQL manual, o que não é um produto. É preciso criar essas contas por fluxos próprios, sem abrir caminho para escalar privilégio, sem segredo no repositório e sem deixar conta com senha conhecida por terceiros em uso normal.

## Decisão
1. **Perfil ADMIN** (migration V3): administra contas e **não tem acesso a agendas, clientes, serviços, equipes nem horários**. Cada rota de negócio exige o perfil na ROTA (`SecurityConfig`, antes de ler o corpo) e de novo na anotação do método; as rotas que antes só exigiam "estar logado" (horários livres e cancelamento) passaram a listar os perfis permitidos (CUSTOMER, PROVIDER, ATTENDANT). Nenhum atalho `hasAnyRole('ADMIN', ...)`.
2. **O primeiro ADMIN nasce só de um comando explícito.** Nenhuma migration insere usuário. O comando é uma execução única da própria aplicação com `queuecare.bootstrap-admin.enabled=true` (padrão: desligado), lê `ADMIN_EMAIL` e `ADMIN_PASSWORD` do ambiente, cria o ADMIN, grava a auditoria e **encerra** (código 0 = criado ou já existia; 1 = erro). Regras: idempotente (se existe QUALQUER ADMIN, não faz nada; nunca reativa, nunca troca senha nem e-mail); serializado no banco por `pg_advisory_xact_lock` (comandos simultâneos criam no máximo um); entrada inválida falha SEM repetir e-mail nem senha (nem em mensagem, nem em log; em falha inesperada loga só o tipo da exceção); senha de pelo menos 12 caracteres (e no máximo 72 bytes, limite do BCrypt); e-mail que já é de conta não ADMIN é recusado sem alterá-la.
3. **Criação atômica de prestador:** `POST /api/admin/providers` (só ADMIN) cria o usuário PROVIDER, os dados do negócio e a auditoria na MESMA transação: qualquer falha desfaz tudo. A senha inicial é escolhida pelo ADMIN (mesma política) e nunca volta em resposta.
4. **Criação atômica de atendente:** `POST /api/provider/staff/accounts` (só o PROVIDER dono) cria um usuário ATTENDANT NOVO, o vínculo ao PRÓPRIO negócio e a auditoria numa transação. O perfil e o prestador nunca vêm do corpo (campos como `role` ou `providerId` são ignorados). Só cria identidade nova: e-mail já usado por qualquer conta é 409, sem reaproveitar nem alterar a conta existente. (Vincular um atendente que já existe continua em `POST /api/provider/staff`, ADR 0004.)
5. **Um único ponto cria usuários** (`AccountProvisioner`): política de senha, e-mail normalizado e único (a palavra final é o índice único do banco, com tradução do 23505 em 409), perfil sempre definido pelo código chamador. O cadastro público passou a usar o mesmo ponto.
6. **Troca de senha obrigatória** (`users.must_change_password`, verdadeiro nas contas criadas por outra pessoa): o conversor do token lê a coluna do BANCO a cada requisição; conta pendente recebe SÓ a autoridade `PASSWORD_CHANGE_REQUIRED` (nenhum perfil) e um filtro responde **403 `PASSWORD_CHANGE_REQUIRED`** em qualquer rota que não seja `GET /api/me` ou `POST /api/auth/change-password` (o caminho é casado como nas regras de autorização, então `/api/me/`, `//api/me` etc. não abrem passagem). Vale na hora, com o mesmo token, nos dois sentidos. A rota de troca de senha em si entra no PR seguinte; o login e `/api/me` já informam `mustChangePassword`.
7. **Auditoria mínima e somente de acréscimo** (`audit_log`): quem (ator), o quê (`ADMIN_BOOTSTRAPPED`, `PROVIDER_ACCOUNT_CREATED`, `ATTENDANT_ACCOUNT_CREATED`), sobre quem (id e perfil) e quando. **Sem colunas livres**: nunca senha, hash, token nem e-mail. O banco recusa UPDATE e DELETE (gatilho) e as chaves estrangeiras são RESTRICT (usuário auditado não é apagado). Gravada na mesma transação da operação (`MANDATORY`): ou as duas coisas acontecem, ou nenhuma. TRUNCATE é questão de privilégio do papel do banco em produção, não de gatilho.

## Como executar o bootstrap do ADMIN
```bash
# Variáveis só para este comando (não vão para arquivo versionado nem para o histórico do shell se você usar read -s)
export ADMIN_EMAIL=seu-email-de-administrador
read -s -p "Senha do ADMIN: " ADMIN_PASSWORD && export ADMIN_PASSWORD
# (as variáveis do banco e JWT_SECRET também precisam estar no ambiente, como em qualquer execução)
java -jar backend/target/queuecare-api-*.jar --queuecare.bootstrap-admin.enabled=true --server.port=0
unset ADMIN_PASSWORD ADMIN_EMAIL
```
- Código de saída 0 e a mensagem "ADMIN criado" (ou "já existe um ADMIN; nada foi alterado"); 1 = erro (a mensagem diz qual regra falhou, sem repetir os valores). `--server.port=0` evita disputar a porta da aplicação que já esteja rodando.
- **Rotação:** troque a senha do ADMIN pela troca de senha (PR seguinte). O comando NÃO serve para trocar senha: com ADMIN existente ele não faz nada, de propósito.
- **Recuperação** (único ADMIN perdeu o acesso): outro ADMIN redefine a senha (PR seguinte). Se não houver outro, só quem tem acesso ao banco, gerando um hash BCrypt fora do banco e atualizando a linha do usuário, com registro do motivo; isso é uma operação de emergência manual e não faz parte da aplicação.

## Alternativas descartadas
- **Inserir o ADMIN em migration:** a senha ficaria no repositório ou seria conhecida; contraria a regra de nenhum segredo versionado.
- **Rota pública ou com chave fixa para criar o primeiro ADMIN:** superfície de ataque permanente para resolver um problema que acontece uma vez.
- **Gerar a senha inicial no servidor e devolvê-la na resposta:** o segredo passaria a trafegar na resposta e em possíveis logs de proxy; a senha definida pelo ADMIN/prestador tem a mesma exposição e já passa pela política e pelo teste.
- **ADMIN com acesso de leitura às agendas "para suporte":** amplia o dano de uma conta ADMIN comprometida; suporte a dados de clientes fica fora do MVP.

## Consequências e limitações conhecidas
- O 409 por e-mail repetido diz ao prestador autenticado que aquele e-mail já tem conta (enumeração limitada a quem já é prestador). Aceito para não duplicar contas.
- O bootstrap define o ADMIN com `must_change_password = false` porque a rota de troca só chega no PR seguinte; depois dele a senha do ADMIN deve ser rotacionada.
- Ainda não há desativar/reativar usuário nem reset de senha (PRs seguintes).
