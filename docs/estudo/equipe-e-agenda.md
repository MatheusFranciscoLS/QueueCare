# Estudo: equipe e agenda (PR 22)

## Em uma frase
O prestador cuida da equipe e o dono e o atendente enxergam e cancelam os agendamentos do dia, sempre nos horários do fuso do negócio.

## O que existe
- **Equipe (prestador):** lista; cria conta de atendente (com "Gerar senha"); vincula quem já tem conta; desvincula ou desativa a conta, sempre com confirmação.
- **Agenda (prestador e atendente):** dia a dia (anterior, hoje, próximo, data), horários no fuso do negócio, cancelamento com confirmação só para agendamento "Agendado".
- **Backend:** `GET /api/agenda/workplace` (negócio e fuso de quem pergunta, sem aceitar o prestador na requisição) para o atendente saber o fuso; a regra de acesso é a mesma da agenda (extraída para um único método).
- Menus: prestador (Meu negócio, Serviços, Expediente, Equipe, Agenda) e atendente (Agenda).

## Como ler o código
1. `api/staff.ts`, `api/agenda.ts`, `api/dates.ts` (hoje no fuso, soma de dias, hora local).
2. `pages/provider/TeamPage.tsx` e `pages/agenda/AgendaPage.tsx`.
3. `AgendaService.resolveProvider` e `AgendaController.workplace` no backend.

## Provas por teste (146 no front, 402 no backend)
- Datas puras: hoje no fuso do negócio (não do aparelho), soma de dias atravessando mês, ano e fevereiro bissexto, datas inválidas, hora em fusos diferentes.
- Senha gerada: aleatória, válida, sem caracteres ambíguos.
- Equipe: criar, validar, e-mail repetido com resposta uniforme, vincular (inexistente, já vinculado, sucesso), confirmações (nada enviado antes), bloqueio durante a gravação, falha ao atualizar.
- Agenda: horários convertidos para o fuso, navegação entre dias com as datas certas, lista do dia anterior some na hora (mutação sem a chave por data falha), resposta lenta de outro dia não aparece, cancelar com confirmação, erros de cancelamento na linha, atendente sem vínculo, prestador sem negócio.
- Perfis: atendente só vê a agenda; cliente e administrador não têm menu e são devolvidos ao início.
- Backend: `workplace` devolve só o negócio de quem pergunta (ignora `providerId`), recusa atendente sem vínculo, cliente e anônimo, e 404 para prestador sem negócio.

## Três perguntas de entrevista
1. **Por que a agenda do atendente precisa de uma rota para o fuso?** Os horários são guardados em UTC e "o dia" é do fuso do negócio; o atendente não tem acesso aos dados do prestador, então a rota entrega só o necessário.
2. **Por que "hoje" não usa o relógio do aparelho?** O prestador pode estar em outro fuso; "hoje" é o dia do negócio.
3. **Por que a lista de cada dia tem a data como chave?** Para que trocar de dia descarte o estado do dia anterior e nunca mostre a lista de ontem sob a data de amanhã.

## Limitações e o que falta
- A agenda não atualiza sozinha (precisa recarregar o dia); check-in e fila ainda não existem (roadmap).
- Reserva pelo cliente depende do endpoint de listar prestadores.

## Revisão: dois ajustes
- "Hoje" calcula a data NO MOMENTO do clique (a tela pode ficar aberta de um dia para o outro); teste com relógio simulado atravessando a meia-noite do negócio.
- Depois de cancelar, desvincular ou desativar com SUCESSO, o registro sai da lista na hora, mesmo que o GET seguinte falhe (o aviso de dados desatualizados continua); assim a tela não oferece uma ação que só receberia 409 ou 404.
- **Senha provisória (3ª revisão):** depois de criar a conta, a senha que o prestador enviou continua visível num painel de entrega (só leitura), com "Copiar senha", aviso de canal seguro e de que não será mostrada de novo, e "Já entreguei, limpar da tela". Só em memória (nunca em localStorage, sessionStorage ou cookie), some ao sair da tela ou da conta, e uma falha na criação nunca a apresenta como se a conta existisse.
