# ADR 0017: remarcar agendamento

- **Status:** Proposed (aguarda a revisão do ChatGPT e a aprovação do Matheus antes de qualquer código)
- **Data:** 16/11/2026
- **Esforço:** ALTO (muda o histórico no banco, a concorrência dos agendamentos e três serviços)
- **Contexto de produto:** no banho e tosa mudar o horário é rotina. Hoje o cliente cancela e reserva de novo: são duas operações, a vaga pode ser perdida entre as duas, o prazo de cancelamento pode barrar quem só quer mudar de horário e o histórico vira dois agendamentos sem ligação.

## Decisão proposta

### 1. Remarcar é MOVER o mesmo agendamento
A operação altera `starts_at` e `ends_at` da **mesma linha** (mesmo id): prestador, serviço, cliente, pet e a cópia dos dados do pet (ADR 0016) ficam como estão, e o status continua `SCHEDULED`.
- **Descartado:** cancelar e criar outro agendamento (duas linhas, id novo, histórico partido, dupla cópia do pet e a vaga pode escapar entre os dois passos); criar o novo e cancelar o antigo na mesma transação (atômico, mas ainda muda o id e infla o histórico).

### 2. Escopo: só o horário
Não troca prestador, serviço nem pet (isso continua sendo cancelar e agendar de novo). A **duração é a do agendamento** (`ends_at - starts_at`), mesmo que o serviço tenha sido editado depois. Pet arquivado **não impede** remarcar (o agendamento já existe; ADR 0016). Agendamento antigo sem pet (`legacy_without_pet`) também pode ser remarcado.

### 3. Quem pode, e quando
- **CUSTOMER dono:** só enquanto o agendamento **atual** começar com pelo menos `cancellationDeadlineMinutes` de antecedência (a mesma política do cancelamento: remarcar em cima da hora seria cancelar disfarçado) → `422 TOO_LATE`. Limite de **3 remarcações por agendamento** (anti-abuso: segurar vaga mudando sem parar) → `422 RESCHEDULE_LIMIT`.
- **PROVIDER e ATTENDANT** do prestador: sem prazo e **sem limite**. **Decisão confirmada pelo Matheus (delegou ao melhor argumento verificado com o ChatGPT):** o limite de 3 é controle de abuso contra um ator externo (segurar vaga mudando sem parar); a equipe é autenticada, vinculada ao estúdio e **auditada**, e remarca por telefone em dia de imprevisto. Para ator confiável a salvaguarda certa é a **rastreabilidade, não a cota**: toda remarcação grava quem fez, quando e de qual horário para qual, e esse histórico é **consultável** (item 9). Descartados: limite alto (regra arbitrária, sem justificativa de domínio), teto técnico de sanidade (desnecessário no MVP) e confirmação extra na tela (ajuda a UX, mas não é controle de domínio). Se a operação real mostrar abuso, um controle pode ser acrescentado com dados. O atendente sem vínculo recebe 403 antes de qualquer consulta, como no cancelamento.
- **ADMIN:** sem acesso. Agendamento de outra pessoa ou inexistente: a **mesma resposta 404 `APPOINTMENT_NOT_FOUND`**.
- Só agendamento `SCHEDULED`: cancelado → `409 ALREADY_CANCELLED`; chegou ou concluído → `409 NOT_RESCHEDULABLE`.

### 4. Regras do novo horário: as MESMAS da reserva, sem duplicar código
O novo horário passa por R5 (fuso, horário local inexistente ou ambíguo, passado e antecedência mínima), R1 (expediente) e R2 (sobreposição), para cliente **e** equipe. A diferença entre os dois é só o prazo do horário atual e o limite de remarcações. Em vez de copiar a lógica do `BookingService`, ela é extraída para um componente compartilhado que as duas operações usam (regra de negócio em um lugar só).
- **Mesmo horário** do atual → `422 NO_CHANGE`.
- **Deslocar para um horário que sobreponha o próprio atual é permitido:** o agendamento não conflita consigo mesmo. A checagem prévia de sobreposição exclui o próprio id e a restrição de exclusão do banco (ADR 0002) já ignora a própria linha.
- Prestador desativado: o novo horário é recusado como na reserva (`PROVIDER_NOT_FOUND`).

### 5. Histórico: novo tipo de evento `RESCHEDULED`, que pode repetir
A V6 (ADR 0011) impõe **no máximo um evento de cada tipo por agendamento**, e a fila depende disso para `CHECKED_IN`. Remarcar pode acontecer mais de uma vez, então a **migration V11**:
- troca `UNIQUE (appointment_id, event_type)` por um **índice único parcial** `WHERE event_type <> 'RESCHEDULED'` (as demais transições continuam no máximo uma vez, a fila não muda);
- amplia o `CHECK` de `event_type` com `RESCHEDULED`;
- acrescenta `previous_starts_at` e `new_starts_at` ao evento (nulos nos outros tipos; um `CHECK` exige os dois se e somente se o tipo for `RESCHEDULED`). O ator é quem remarcou.
O limite de 3 remarcações do cliente é contado por esses eventos, dentro da transação protegida pelo `version` (item 6).

### 6. Concorrência e integridade
- **Mesma linha:** bloqueio otimista por `version` (padrão de check-in, conclusão e cancelamento). Remarcar, check-in, cancelar e a atualização da cópia do pet (que sobe o `version`) serializam por aí; quem perde recebe `409 CONCURRENT_UPDATE`. A contagem do limite de remarcações é lida na mesma transação, então duas remarcações simultâneas do mesmo agendamento não passam do limite (uma delas falha por versão).
- **Horário novo ocupado por outro agendamento:** decide a restrição de exclusão. O erro `23P01` é traduzido para `409 SLOT_TAKEN` depois do rollback (padrão da reserva). **Troca de horários** (A vai para onde B está e B para onde A está) pode dar impasse; o PostgreSQL o detecta (`40P01`) e ele já é tratado como conflito de horário.
- **Espera de bloqueio limitada** (`RowLocks`, 3 s): estourou, `409 RESOURCE_BUSY`; nunca espera infinita.
- **Ordem de bloqueios** do ADR 0016 (cliente, pet, agendamento): remarcar toca só o agendamento, não lê nem bloqueia o pet, então não forma ciclo com editar ou arquivar pet.
- **Uma transação só:** o `UPDATE` do horário e a gravação do evento terminam juntos ou nenhum (teste com gatilho que quebra a gravação do evento, como na reserva).

### 7. API
`POST /api/appointments/{id}/reschedule` com `{ "localStart": "AAAA-MM-DDTHH:mm:ss" }` (horário **local** do prestador, como na reserva) → 200 com o mesmo `AppointmentResponse` (inclui o pet). Perfis `CUSTOMER`, `PROVIDER`, `ATTENDANT`; quem chama vem do token; nada de prestador ou cliente no corpo. Erros, todos com `reason` estável: 400 (corpo inválido), 404 `APPOINTMENT_NOT_FOUND`, 409 `SLOT_TAKEN`, `ALREADY_CANCELLED`, `NOT_RESCHEDULABLE`, `CONCURRENT_UPDATE`, `RESOURCE_BUSY`, 422 `TOO_LATE`, `RESCHEDULE_LIMIT`, `NO_CHANGE` e os da reserva (`IN_THE_PAST`, `TOO_SOON`, `OUTSIDE_AVAILABILITY`, `NONEXISTENT_LOCAL_TIME`, `AMBIGUOUS_LOCAL_TIME`).

### 8. Horários livres que ignoram o próprio agendamento
Hoje `GET /api/providers/{id}/slots` esconde o intervalo do próprio agendamento (ele conta como ocupado), então a tela não ofereceria deslocar de 10:00 para 10:30 num serviço de 60 minutos. Proposta: parâmetro opcional `excludeAppointmentId`, **ignorado** se o agendamento não for do chamador (cliente dono, ou equipe do prestador): não revela nada. Entra no PR de backend, pequeno.

### 9. Histórico consultável, não só gravado
`GET /api/appointments/{id}/events` (somente leitura): lista os eventos do agendamento **por instante (`created_at`) e, em empate, por id** (ordem determinística), com tipo, instante e, nas remarcações, o horário anterior e o novo. Quem pode: a **equipe** do prestador (com o nome de quem agiu) e o **cliente dono** (só se a ação foi "você" ou "equipe", sem nome de funcionário). Agendamento de outra pessoa ou inexistente: a mesma 404. Fica no PR de backend, pequeno.

### Testes de autorização do histórico (obrigatórios no PR de backend)
- **Cliente dono** vê o histórico **sem nomes de funcionários** (só "você" ou "equipe").
- **Equipe vinculada ao prestador** vê os nomes de quem agiu.
- **Cliente alheio** recebe a **mesma 404** que um id inexistente (sem pista de existência).
- **Equipe de outro prestador** não acessa (mesma 404); atendente sem vínculo recebe 403 antes de qualquer consulta.
- **ADMIN** não acessa.
- Dois eventos no **mesmo instante** saem sempre na mesma ordem (desempate por id).

## Testes que provam a diferença entre os papéis (obrigatórios no PR de backend)
- Cliente: a 4ª remarcação do mesmo agendamento recebe 422 `RESCHEDULE_LIMIT`; as 3 primeiras passam.
- Equipe: a 5ª, a 10ª e a 20ª remarcações do mesmo agendamento são aceitas (respeitando as demais regras de horário).
- Cada remarcação gera **exatamente um** evento `RESCHEDULED`, com o ator, `previous_starts_at` e `new_starts_at` corretos e o instante da operação.

## Plano (PRs pequenos e revisados)
1. ADR (este) aprovado.
2. **PR de backend, ALTO:** V11, extração das regras de horário, serviço de remarcação, endpoint, parâmetro de horários livres, histórico consultável, OpenAPI, testes de acesso, concorrência (versão, horário disputado, troca de horários, limite de 3 sob corrida), atomicidade e migration sobre dados.
3. **PR de tela do cliente, MÉDIO:** "Remarcar" em Meus agendamentos (escolha de dia e horário reutilizando os passos de agendar; limites e prazo explicados).
4. **PR de tela da equipe, MÉDIO:** "Remarcar" na agenda do dia.

## Riscos a atacar na revisão
Extração das regras sem mudar a reserva (os testes do `BookingService` e da concorrência da reserva precisam continuar iguais); a migration da V6 (índice único parcial) sobre banco com dados; remarcar x check-in x cancelar x edição de pet (todos mexem no mesmo `version`); troca de horários e impasse; limite de 3 sob corrida; o parâmetro `excludeAppointmentId` não vazar existência de agendamento alheio; histórico legível para o prestador (`previous_starts_at`, `new_starts_at`).
