# Estudo: pet obrigatório (PR 49)

## O que é
Todo agendamento **novo** passa a ter pet. Os que já existiam (sem pet) continuam valendo e podem mudar de estado normalmente. A tela já exigia o pet (PR 48); agora a API e o banco também exigem.

## Decisões que valem entrevista
- **A regra vive no serviço de agendamento, não no controlador.** `BookingService.book` recusa `petId` nulo com `PET_REQUIRED` (HTTP 400) **antes de qualquer consulta**. Se amanhã surgir outro caminho que crie agendamento (remarcar, reserva sem conta), ele passa pelo mesmo serviço e herda a exigência, o bloqueio do pet e a cópia dos dados. Um teste estrutural falha se outra classe criar `Appointment`.
- **O banco é a última palavra.** `CHECK (pet_id IS NOT NULL OR legacy_without_pet)`: mesmo que uma rota esquecesse a regra, o PostgreSQL recusa agendamento novo sem pet.
- **Por que uma marca e não uma data de corte.** A ideia óbvia seria "linhas criadas antes da migration dispensam o pet", mas `created_at` pode ser informado: alguém retroagiria a data e escaparia da regra. A marca `legacy_without_pet` é escrita **só pela migration**, nas linhas que já existem sem pet; um gatilho recusa `INSERT` com a marca verdadeira e qualquer `UPDATE` que a mude. Não depende de data, de relógio nem da aplicação (a entidade JPA nem mapeia a coluna).
- **A migration roda sobre dados que já existem.** Marca primeiro as linhas antigas, só então cria a regra e o gatilho (se fosse ao contrário, a própria migration seria recusada). Testada num banco migrado até a V9, com dados antigos, e depois até a V10.
- **Compatibilidade da API:** `petId` passa a ser obrigatório no contrato OpenAPI (`required`); quem não envia recebe 400 `PET_REQUIRED` (código estável, em vez do 400 genérico de validação). A tela já enviava, então nada muda para o cliente.

## Testes
- `PetRequiredConstraintsTest` (6, PostgreSQL real): migration, agendamento novo sem pet recusado, com pet aceito, ninguém se declara antigo (INSERT com a marca, nem retroagindo `created_at`), a marca não muda em linha existente, e a migration sobre um banco com dados antigos (só a linha sem pet é marcada; ela ainda muda de estado; a marca não se altera; agendamento novo sem pet continua recusado).
- `BookingServiceIntegrationTest` (+1): sem pet a reserva é recusada antes de qualquer consulta e nada é gravado. `PetAppointmentApiIntegrationTest`: sem `petId` (ausente ou nulo) dá 400 `PET_REQUIRED`; agendamento antigo aparece com `pet` nulo. `PetWriteProtocolTest` (+1): só o `BookingService` cria `Appointment`.
- Os testes antigos que gravavam agendamento sem pet (14 pontos de SQL direto e todas as reservas por API e por serviço) agora usam um pet de teste (`petOf`, `petOfToken`, `bookingRequest`): o custo previsto no ADR (regressão dos testes que reservavam sem pet).
- **Mutação verificada** (8, todas derrubadas): serviço sem exigir o pet; sem o `CHECK`; gatilho sem a guarda de `INSERT`; gatilho sem a guarda de `UPDATE`; migration marcando todas as linhas; migration sem marcar as antigas; `PET_REQUIRED` com outro status HTTP; um segundo ponto que cria `Appointment`.

## Limitações e o que falta
- O gatilho e a regra protegem contra erro da aplicação, não contra quem tem acesso de administrador ao banco (que poderia desligar o gatilho). É a mesma fronteira de confiança das demais restrições.
- Agendamentos antigos continuam sem pet para sempre (não há migração de dados que invente um pet); o prestador os vê sem a linha do pet.
- Remarcar (próximo ADR) precisa decidir o que acontece com o pet ao mover o horário e com agendamentos antigos sem pet.

## Perguntas de entrevista
1. Por que a exigência fica no serviço (e no banco) e não só no controlador ou na tela?
2. Por que uma marca escrita só pela migration é mais segura que uma data de corte em `created_at`? Que ataque a data permitiria?
3. Por que a migration marca as linhas antigas antes de criar o `CHECK`? O que acontece na ordem inversa?
