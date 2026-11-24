# ADR 0041: demonstração pública com contas fictícias compartilhadas

- **Status:** Accepted (implementado em 24/11/2026; ligada em produção com `DEMO_ENABLED=true`)
- **Data:** 24/11/2026
- **Esforço:** MÉDIO (um serviço, uma tabela, um filtro, três botões, 8 testes de backend e 5 de frontend)

## Contexto
O QueueCare é um projeto de portfólio: quem avalia abre o site e, antes desta decisão, só via a tela de entrada. O lado do estúdio (agenda, fila, clientes, resumo), que é onde o sistema mostra o que sabe fazer, só rodava no computador de quem clonasse o repositório (`npm run demo`, ADR 0018). Para quem avalia, "clone e rode" é uma barreira que quase ninguém atravessa; e criar a conta de tutor do site mostra só a parte do cliente.

## Decisão
**Três botões na tela de entrada ("Entrar como dona do estúdio / atendente / tutora") que entram, sem cadastro e sem senha, num estúdio FICTÍCIO** com a mesma massa de dados do `npm run demo`: equipe, tutores, pets e agendamentos em todos os estados.

- **Só existe quando ligada.** `queuecare.demo.enabled` (variável `DEMO_ENABLED`) é falso por padrão. Desligada, a rota `POST /api/auth/demo` **nem existe** (404, não aparece no contrato OpenAPI) e nenhuma conta fictícia é criada (teste `DemoDisabledIntegrationTest`). No site, os botões só aparecem no build publicado (`frontend/.env.production`, `VITE_DEMO=true`); em desenvolvimento e nos testes, não.
- **Sem senha utilizável.** As contas têm senha aleatória que ninguém conhece (hash BCrypt de um UUID descartado): o formulário de login nunca entra nelas (teste), só o botão, que emite o token direto. Os botões **nunca** entram como administrador (teste: o perfil `ADMIN` é rejeitado).
- **Domínio reservado.** As contas usam `@demo.invalid` (`.invalid` é reservado pela RFC 2606 e nunca recebe e-mail); o cadastro público não cria contas ali, então o sufixo identifica a conta de demonstração sem uma coluna nova.
- **Reposição sem agendador.** Os dados voltam ao original **na hora do clique, se passou o prazo** (60 min, `DEMO_RESET_AFTER`). Agendador não serve: no plano gratuito a API dorme e o agendamento do GitHub Actions não roda (ADR 0037). O horário da última reposição fica numa tabela de uma linha (`demo_state`, migração V29, só expande); uma trava consultiva do PostgreSQL faz dois cliques simultâneos repor uma vez só. Quem entra dentro do prazo vê o que a pessoa anterior deixou, e o aviso fixo na tela diz que tudo é fictício e reposto de tempos em tempos.
- **Operações sobre a própria conta ficam travadas** (403, motivo `DEMO_READ_ONLY`, só para contas de demonstração): trocar a senha, excluir a conta, cadastrar atendentes e gerar senha temporária para tutor. As contas são compartilhadas por todos os visitantes; sem a trava, o primeiro visitante trocaria a senha de todos, e a conta de atendente criada ficaria para sempre na auditoria (que é só de acréscimo). Conta real passa direto (teste).
- **O que visitantes digitam não sobrevive.** Tutores cadastrados pelo balcão da demonstração têm o cadastro na auditoria, que não permite apagar. Na reposição eles são **desligados do estúdio, desativados e anonimizados** (nome, telefone e e-mail somem; teste). Os demais dados do estúdio fictício são apagados e recriados.
- **Limite de abuso:** a rota tem o mesmo limite por minuto, por endereço, do login (`RateLimitedRoutes`).

## O que foi descartado
- **Uma conta por visitante** (sandbox individual): isolaria cada pessoa, mas cria contas e dados em produção sem fim e exige limpeza própria. Para um portfólio, contas compartilhadas com reposição são suficientes.
- **Reposição por agendador** (GitHub Actions ou serviço externo): depende de algo que não roda de forma confiável no plano gratuito (ADR 0037) e de conta de terceiros.
- **Entrada como administrador:** o administrador mexe em todos os estúdios e contas; não faz sentido num ambiente aberto.
- **Interruptor só no código do site:** o botão sozinho mostraria um erro se a API estivesse com a demonstração desligada; por isso a API também tem o seu interruptor e a mensagem de erro explica.
- **Demonstração em outro banco:** custo e uma segunda hospedagem. O estúdio fictício convive com os dados de teste do projeto; o segredo do banco e as contas reais não mudam.

## Riscos conhecidos
- **Visitantes simultâneos se atrapalham** (um cancela o que o outro olhava). Aceito: é uma vitrine, não um ambiente de trabalho, e a reposição corrige em até uma hora.
- **O estúdio fictício aparece no catálogo de qualquer tutor real** (é um estúdio publicado). Hoje só há dados de teste em produção; se houver estúdios reais, desligar a demonstração ou publicar o estúdio fictício só com marca de demonstração.
- **A reposição leva alguns segundos** (a primeira vez cria as contas, com BCrypt, que no plano gratuito é lento); só o primeiro clique depois do prazo espera.
- **Tutores anonimizados acumulam** como linhas desativadas (uma por tutor que um visitante cadastrou, no máximo 60 por hora pelo limite do balcão): irrelevante em volume.

## Como desligar
Em `DEMO_ENABLED` no Render, `false` (ou apagar a variável): a rota some e os botões passam a mostrar a mensagem de indisponível. Para tirar também os botões do site, apagar `frontend/.env.production`.
