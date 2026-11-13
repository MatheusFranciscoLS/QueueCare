# ADR 0004: vínculo atendente-prestador e autorização da equipe

- **Status:** Accepted
- **Data:** 2026-11-13
- **Quem decidiu:** o Matheus delegou; desenho de Claude revisado pelo ChatGPT (revisor independente) com esforço alto, por ser autorização multiusuário (um erro poderia expor dados de um prestador a outro).

## Contexto
A regra R4 diz que o atendente "administra a agenda do prestador". Até o PR 14 o atendente recebia 403 porque o modelo não ligava atendente a prestador. É preciso um vínculo seguro, sem abrir caminho para um atendente enxergar ou alterar dados de outro prestador.

## Decisão
1. **Um atendente pertence a no máximo um prestador** (`UNIQUE(user_id)` em `provider_staff`). Evita escolher "contexto de prestador" em cada chamada, que é a classe de bug "atendente opera no prestador errado". Virar N:N exigiria um mecanismo explícito e seguro de seleção de contexto.
2. **Só ATTENDANT pode ser vinculado, garantido NO BANCO:** `UNIQUE(id, role)` em `users` e FK composta `(user_id, role) -> users(id, role)` com `CHECK (role = 'ATTENDANT')`. Efeitos: não se vincula cliente nem prestador; o perfil de quem está vinculado não muda nem o usuário é apagado enquanto houver vínculo (para promover alguém é preciso desvincular antes); prestador com equipe não é apagado. O código sempre grava `ATTENDANT`.
3. **Quem vincula:** só o PROVIDER dono, no PRÓPRIO negócio (`POST/GET /api/provider/staff`, `DELETE /api/provider/staff/{userId}`). O prestador vem do token; campos extras no corpo (`role`, `providerId`) são ignorados. O vínculo usa o **id do usuário**; o e-mail (normalizado) é só a chave de busca ao vincular. Criar usuários ATTENDANT continua fora deste PR.
4. **Respostas:** e-mail inexistente, de quem não é atendente ou inativo: **404 `STAFF_NOT_FOUND`, a mesma resposta** (sem enumeração). Atendente já vinculado, a este ou a outro prestador: **409 `STAFF_ALREADY_ASSIGNED`**, sem revelar detalhes; o POST não é idempotente. Desvincular alguém que não é do meu negócio: 404 igual a "não existe".
5. **`StaffAccess` é o ponto único de confiança** ("de qual prestador este usuário opera?"): PROVIDER = o negócio do usuário; ATTENDANT = o vínculo atual; CUSTOMER, inativo ou sem vínculo = vazio. O perfil e o "ativo" vêm do **banco**; o vínculo é lido **a cada chamada, sem cache e sem o prestador no JWT**, então desvincular, desativar ou trocar de prestador vale na hora, sem novo token. Vazio nunca vira prestador padrão: cada caso de uso decide (403, 404).
6. **O que a equipe passa a poder:** (a) **cancelar** agendamentos do prestador em que opera, **sem prazo**; agendamento de outro prestador = 404 (igual a "não existe"); atendente sem vínculo = 403 antes de qualquer consulta ao agendamento; (b) **agenda do dia** `GET /api/agenda?date=` para PROVIDER e ATTENDANT: agendamentos não cancelados do prestador resolvido pelo servidor (nunca por parâmetro; `providerId` na URL é ignorado), data local do prestador convertida no intervalo de instantes `[início do dia, início do dia seguinte)` (correto em dias de 23 h e de 25 h), com **id e nome do cliente** e sem e-mail ou outros dados pessoais. O atendente **não** agenda em nome de cliente neste PR.
7. **Tradução de erros de integridade específica:** só a violação `provider_staff_user_uk` (23505) vira 409 e a FK composta (23503, usuário deixou de ser atendente no meio) vira 404; qualquer outro erro é relançado.

## Alternativas descartadas
- N:N entre atendente e prestador: exige escolher contexto a cada chamada (mais superfície de IDOR) sem necessidade comprovada.
- Guardar o prestador no JWT ou em cache: o acesso sobreviveria a desvincular/desativar até o token expirar.
- Aceitar `providerId` como parâmetro com checagem depois: o desenho "o servidor resolve" elimina a classe de erro.

## Consequências
- **Semântica da revogação:** desvincular, desativar ou trocar de prestador vale **a partir da próxima requisição**. Uma requisição já em andamento continua com o vínculo que observou no início (não há bloqueio entre a resolução do prestador e a operação); isso não dá acesso a outro prestador, apenas não retroage. Um bloqueio por usuário pode vir depois se o produto exigir.
- **Agenda e clientes desativados:** a agenda mantém o **nome histórico** do cliente mesmo depois de ele ser desativado (os ids vêm dos agendamentos do próprio prestador; e-mail nunca é exposto). Se a política de privacidade pedir anonimização, decidir em PR próprio.
- Cada requisição de equipe faz 1 a 2 consultas extras ao banco (custo aceito pela revogação imediata).
- Limitações conhecidas: um atendente por um prestador; sem fluxo para criar usuários ATTENDANT/PROVIDER (PR seguinte); sem log de auditoria do vínculo além de `created_at`; sem rate limit.
- Testes (`docs/estudo/vinculo-atendente-e-agenda.md`): constraints no PostgreSQL real, `StaffAccess` sem cache, e uma **matriz adversária por HTTP** com tokens reais (dois prestadores, dois atendentes vinculados, um solto, dois clientes). Foi verificado que a matriz falha quando a checagem de prestador é quebrada de propósito.
