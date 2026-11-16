# Estudo: reserva com pet, cópia e leitura (PR 47)

## O que é
Agora o agendamento pode apontar para um pet do cliente. Ao reservar, o sistema **copia** os dados do pet para o agendamento (snapshot). A agenda do dia, a fila, a semana e "Meus agendamentos" leem essa **cópia**. O campo `petId` continua opcional na API (o PR 49 o torna obrigatório); agendamentos antigos ficam sem pet.

## Decisões que valem entrevista
- **Reservar x arquivar.** Ler `active` e depois gravar a reserva deixa uma janela: entre a leitura e a gravação o tutor pode arquivar o pet. A reserva então bloqueia a **linha do pet** (`FOR NO KEY UPDATE`, a mesma que o arquivamento bloqueia) e só depois confere `active`. Quem chega primeiro vence: se a reserva vence, ela vale e o arquivamento seguinte é permitido; se o arquivamento vence, a reserva recebe 422 `PET_INACTIVE`. A transação compartilhada, sozinha, não resolve (em READ COMMITTED a leitura não trava a escrita alheia).
- **Pet alheio = pet inexistente.** O dono vem na consulta do bloqueio; reservar com o pet de outro cliente (ou arquivado, ou inventado) dá a mesma resposta 404 `PET_NOT_FOUND`. A conferência do dono vem antes da de ativo. O banco repete a regra: a chave estrangeira é composta (pet, cliente).
- **Cópia (snapshot), com uma exceção estreita.** O histórico não pode mudar quando o tutor edita o pet (um atendimento concluído mostra o que valia no dia). Mas enquanto o agendamento está `SCHEDULED` a cópia **acompanha** a edição (o prestador precisa ver o aviso de coceira que o tutor escreveu depois de marcar). Do check-in em diante ela congela.
- **A edição atualiza em massa, com cuidado.** `UPDATE ... WHERE pet_id = ? AND status = 'SCHEDULED'`, com `version + 1`. Se um check-in estiver em andamento, o `UPDATE` espera a linha e **reavalia** o status: se o check-in venceu, a cópia fica como estava. O `version + 1` faz o bloqueio otimista recusar um check-in que tenha lido o agendamento antes da edição. As colunas da cópia também são `updatable = false` no JPA, então mudar o status nunca reescreve a cópia. A consulta em massa limpa o contexto do JPA depois de gravar o que estava pendente.
- **Autorização na consulta, não na tela.** O prestador só recebe o pet de agendamentos que a consulta dele já devolve (filtra por prestador); o cliente, os dele. Não existe endpoint para ler `notes` por fora disso. Menos dado onde há muitas linhas: a semana não leva raça nem observações.
- **A leitura nunca olha o cadastro.** Um teste altera `pets` por SQL, sem a API, e a agenda continua mostrando a cópia: se alguém trocar a leitura por uma junção com `pets`, o teste falha.

## Testes
- `AppointmentPetConstraintsTest` (8, PostgreSQL real): chave estrangeira composta, consistência da cópia, formato por SQL direto, pet com reservas não apaga.
- `PetAppointmentApiIntegrationTest` (17): cópia gravada e resposta mínima; reserva sem pet; 404 idêntico para pet alheio, alheio arquivado e inexistente; 422 para pet arquivado; agenda do dia completa, semana reduzida, fila completa, atendente vê, outro prestador, outro tutor e ADMIN não veem; leitura da cópia e não do cadastro; edição só nos SCHEDULED e com versão; check-in congela; check-in obsoleto recusado sem sobrescrever; **corridas determinísticas** (uma transação aberta segura a linha enquanto a outra chamada tenta passar): reserva espera o arquivamento e falha com `PET_INACTIVE`; arquivamento espera a reserva; edição espera o check-in e deixa a cópia como estava; mais 12 rodadas simultâneas reservar x arquivar.
- `PetLockTimeoutIntegrationTest` (+1): reserva com o pet bloqueado vira 409 `RESOURCE_BUSY` rápido e funciona depois.
- **Mutação verificada** (10, todas derrubadas): `lockPet` sem bloquear; pet sem conferir o dono; reserva sem conferir `active`; reserva sem copiar o pet; cópia atualizada em qualquer status; atualização sem subir a versão; semana com observações; edição sem atualizar a cópia; `PET_INACTIVE` com outro status HTTP; chave estrangeira sem o cliente.

## Limitações e o que falta
- `petId` ainda é opcional (PR 49 o torna obrigatório e fecha a compatibilidade); telas no PR 48.
- A limpeza do contexto (`clearAutomatically`) não tem mutação própria: nenhum fluxo de produção carrega `Appointment` na mesma transação da edição, então a proteção é por construção (comentário e ADR) e não por teste.
- O limite de espera do bloqueio vale pelo resto da transação de reserva com pet: duas reservas simultâneas do mesmo horário esperam no máximo 3 s uma pela outra (depois, 409 `RESOURCE_BUSY`).

## Perguntas de entrevista
1. Por que a reserva bloqueia a linha do pet antes de conferir se ele está ativo? O que acontece se só ler?
2. Por que a cópia do pet acompanha a edição enquanto o agendamento é `SCHEDULED` e congela depois? Qual a corrida entre editar e fazer check-in, e como o `UPDATE` em massa a resolve?
3. Por que a agenda lê a cópia e não faz junção com o cadastro? O que isso muda para a autorização de `notes`?
