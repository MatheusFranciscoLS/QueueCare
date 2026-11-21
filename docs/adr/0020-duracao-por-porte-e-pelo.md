# ADR 0020: duração do atendimento por porte e por pelo

- **Status:** Accepted (o Matheus delegou a escolha da próxima melhoria em 21/11/2026: "faça o que achar mais recomendado"; esta era a melhoria de maior valor apontada no ADR 0019)
- **Data:** 21/11/2026
- **Esforço:** ALTO (muda domínio, banco, consulta de horários e a ordem das telas do Agendar)

## Contexto
No balcão, o tempo de um serviço depende do pet: um golden de pelo duplo leva bem mais que um poodle pequeno no mesmo "Banho". Hoje o serviço tem **uma** duração fixa, então a agenda do estúdio fica errada (atendimento que estoura o horário seguinte ou horário que sobra vazio).

## Decisão
1. **Serviço ganha três campos opcionais:**
   - `duration_medium_minutes` e `duration_large_minutes` (nulo = usa a duração base, como nos preços do ADR 0019);
   - `coat_extra_minutes` (0 a 240, padrão 0): minutos **somados** quando o pet tem pelo `LONG`, `CURLY` ou `DOUBLE`.
2. **Duração do pet** = duração do porte (`SMALL` ou sem porte = base; `MEDIUM`; `LARGE`) + extra de pelo (só para os três pelos acima; pelo não informado não soma). Mesma faixa do serviço: de 5 a 480 minutos no total, validada na aplicação; o banco repete a faixa de cada campo.
3. **A consulta de horários passa a aceitar `petId`** (opcional). Só vale se o pet for **do chamador** (cliente dono); em qualquer outro caso é ignorado, sem erro e sem pista de que o pet existe (mesmo padrão do `excludeAppointmentId`). Sem `petId`, vale a duração base.
4. **A reserva calcula a duração com o pet da própria reserva** (o mesmo que já é bloqueado e copiado para o agendamento), então horário oferecido e horário aceito nunca divergem. O fim do agendamento (`ends_at`) fica **congelado** na reserva: editar o serviço depois não muda atendimentos já marcados.
5. **Remarcar não recalcula:** usa a duração do próprio agendamento (já era assim, ADR 0017).
6. **Agendar muda de ordem:** prestador, serviço, **pet**, dia, horário, confirmar. O pet vem antes porque o horário depende dele. Com um só pet ativo, ele já vem escolhido (como hoje).
7. **Tela do serviço (estúdio):** campos opcionais "Duração (porte médio)", "Duração (porte grande)" e "Minutos a mais para pelo longo, cacheado ou duplo", com a mesma explicação dos preços. Na escolha do serviço, o tutor vê "30 min" ou "a partir de 30 min" quando a duração varia; depois de escolher o pet, a confirmação mostra a duração exata.

## Fora de escopo
- Duração por raça, por temperamento ou por tutor; tempos separados por etapa (banho, secagem, tosa); mais de um profissional atendendo em paralelo.

## Consequências
- A agenda do estúdio reflete o tempo real do atendimento.
- A consulta de horários fica dependente do pet; mudar de pet refaz a lista de horários.
- Serviços existentes continuam válidos: sem os campos novos, a duração é a base para todos.

## Andamento
Implementado em 21/11/2026 (um único PR: migração V16, cálculo no serviço e na consulta de horários, tela do estúdio e nova ordem do Agendar). Testes: `ServiceDurationIntegrationTest` (backend) e `duracao.test.tsx` (frontend). A consulta de horários lê o pet por `PetService.findActive`, porque a regra de proteção `PetWriteProtocolTest` só deixa o serviço de pets e o `RowLocks` usarem o repositório.
