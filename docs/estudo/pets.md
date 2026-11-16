# Estudo: pets (cadastro do tutor)

## O que é
O banho e tosa atende o **animal**, não só o tutor. Este PR cria o cadastro: o cliente (tutor) registra, edita, arquiva e reativa os próprios pets em `/api/customer/pets`. Ainda **não** liga o pet ao agendamento (PR seguinte), então nada muda para quem já agenda.

## Decisões que valem entrevista
- **Normalizar na aplicação, garantir no banco.** A API apara espaços, junta espaços repetidos e troca CRLF por LF; o PostgreSQL **recusa** (CHECK) tudo o que não está nessa forma, inclusive por SQL direto. O banco não "conserta" texto: ele só impede dado fora do padrão.
- **Limites sem corrida:** 20 pets ativos e 100 no total. Contar e gravar sem trava deixa dois pedidos simultâneos lerem a mesma contagem e passarem do limite. A solução é bloquear **a linha do cliente** (`FOR NO KEY UPDATE`) antes de contar, em todo caminho que torna um pet ativo (criar e reativar). Existe um só método de serviço para isso.
- **Reativar não cria registro:** só o limite de ativos vale; o de 100 vale para criar.
- **Pet alheio = pet inexistente (404).** O dono é parte da consulta SQL; quem tenta ler, editar ou arquivar o pet de outro cliente recebe a mesma resposta de um id que não existe.
- **Sem exclusão física:** arquivar mantém o histórico e deixa o agendamento antigo íntegro.
- **Bloqueios com espera limitada:** se a linha estiver ocupada por mais que o limite, a API responde 409 `RESOURCE_BUSY` em vez de travar ou dar erro 500. Leituras comuns não esperam.
- **Ordem de bloqueios do sistema:** cliente, depois pet, depois agendamento. Seguir sempre a mesma ordem impede impasse (deadlock). O PR 47 usa o bloqueio do pet para a corrida "reservar x arquivar".
- **Dado pessoal:** as observações (`notes`) não aparecem em erros nem em logs; a validação devolve só o motivo por campo.

## Testes
- `PetTextTest` (11): normalização e validação, com pontos de código (emoji conta 1) e a regra dos controles nas pontas (TAB/LF/CR são aparados; sino, NUL, DEL e C1 são recusados em qualquer posição).
- `PetWriteProtocolTest` (3): guarda estrutural. Lê o código e falha se surgir outro ponto que crie ou reative `Pet`, ou se o bloqueio do cliente sair do método único `takeActiveSlot` (o protocolo de criar e reativar).
- `PetConstraintsTest` (9, PostgreSQL real): cada CHECK recusa o que deve, por SQL direto.
- `PetApiIntegrationTest` (15): CRUD, normalização, acesso por perfil (PROVIDER, ATTENDANT e ADMIN recebem 403 em todas as rotas), isolamento entre clientes, limites 20/100 e reativação.
- `PetConcurrencyIntegrationTest` (3, threads de verdade): 30 criações simultâneas terminam com exatamente 20; duas disputando a 20ª vaga, uma passa; criar e reativar dividem as mesmas vagas.
- `PetLockTimeoutIntegrationTest` (2): bloqueio de pet e de cliente viram 409 rápido e soltam depois.
- **Mutação verificada** (quebrei o código de propósito e o teste correspondente falhou): sem o bloqueio do cliente (criar e reativar), limites "fora por um" (ativos e total), reativar conferindo o total, leitura sem conferir o dono, bloqueio do pet sem dono, lista ignorando `includeArchived`, sem colapsar espaços, sem converter CRLF, controle C1 aceito, limite de nome fora por um, três CHECKs do banco removidos, um segundo ponto de criação de `Pet`, o protocolo único sem o bloqueio do cliente e o nome sem `strip()`. Sobrevive de propósito: remover só a regra de rota `/api/customer/**` (o `@PreAuthorize` do controlador cobre sozinho; são duas camadas).

## Limitações e o que falta
- O pet ainda não faz parte do agendamento: reserva com pet, snapshot, agenda e fila (PR 47); telas (PR 48); `petId` obrigatório (PR 49).
- `COLLATE "C"` nos CHECKs não tem teste de mutação: removê-lo não muda nenhum resultado no Windows (localidade Portuguese_Brazil) nem no Linux do CI; ele existe porque a documentação do PostgreSQL avisa que intervalos de regex podem depender da collation. Os testes de inserção direta passam nos dois ambientes.
- Não há teste de mutação para "a espera de bloqueio é limitada" (remover o limite faria o teste travar, não falhar).
- Sem limite de requisições específico nas rotas de pets (os limites de 20 e 100 já contêm o abuso de gravação).

## Perguntas de entrevista
1. Por que ler a contagem e inserir não basta para respeitar "no máximo 20 pets"? O que o bloqueio da linha do cliente muda?
2. Por que o banco recusa texto fora do padrão em vez de corrigir, e por que ainda normalizar na aplicação?
3. Por que 404 para pet de outro cliente, e não 403?
