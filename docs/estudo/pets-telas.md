# Estudo: telas de pets (PR 48)

## O que é
O tutor ganha a tela **Meus pets** (cadastrar, editar, arquivar, reativar) e o agendamento passa a **exigir um pet na tela**. A agenda do dia, a fila e "Meus agendamentos" mostram o pet do agendamento. A API continua aceitando reserva sem `petId` até o PR 49; quem exige é a tela.

## Decisões que valem entrevista
- **A tela exige, a API ainda não.** Tornar `petId` obrigatório na API só depois que a tela o envia (PR 49): assim nenhum cliente fica sem conseguir agendar no meio do caminho.
- **O pet é um passo antes de confirmar** ("5. Escolha o pet"; a confirmação vira "6"). Com **um só pet ativo** ele já vem escolhido (menos um clique para a maioria); com vários, nenhum vem escolhido e **confirmar fica desabilitado** até a escolha. Sem pet nenhum, o formulário de cadastro aparece ali mesmo e o pet novo já fica escolhido.
- **Recusa por causa do pet não derruba o horário.** Se a API responde `PET_INACTIVE` ou `PET_NOT_FOUND` (por exemplo, o pet foi arquivado em outra aba), a tela mostra o motivo, **mantém o horário escolhido**, limpa a escolha do pet e refaz a lista de pets. Qualquer outra recusa continua descartando o horário, como antes.
- **O erro de uma ação mora na lista, não na linha** (a linha pode sumir ao arquivar ou mudar de lugar ao recarregar). Depois de uma ação, com sucesso OU erro, o foco vai para a linha na hora e, quando a lista refeita é desenhada, é conferido de novo: se a linha sumiu (arquivada, ou removida por uma alteração concorrente), o foco vai para o título da página; se continua, fica nela. Nunca fica num botão que deixou de existir.
- **Depois de uma recusa por causa do pet, a lista de pets só vale quando uma carga NOVA der certo.** Se a atualização falhar, os dados antigos continuam à vista (com aviso e "Tentar de novo"), mas nenhum pet pode estar escolhido — nem o único pet que antes vinha escolhido sozinho — e a confirmação fica bloqueada.
- **Arquivar avisa o que isso significa:** os agendamentos já feitos continuam valendo, mas não dá para marcar novos horários com o pet arquivado. A confirmação abre em "Voltar" (a opção segura).
- **Validação nos dois lados, sem repetir o texto.** A tela confere nome, espécie e tamanho antes de enviar; os erros de campo da API aparecem no campo certo; o que o tutor digitou fica no formulário para corrigir.
- **Observações são dado pessoal:** só aparecem para quem a API já enviou (agenda do dia e fila do prestador; o próprio tutor na lista de pets). A semana e "Meus agendamentos" não as recebem.

## Testes
- `pets.test.tsx` (32): lista, contador e filtro de arquivados (inclusive resposta antiga fora de ordem), cadastro (envio, validação local, erro de campo da API, limite, envio duplo), edição, arquivar com confirmação e foco, erro que muda a lista, reativar e o limite, perfis sem acesso, escolha do pet ao agendar (um pet, vários, nenhum, falha ao carregar, pet arquivado em outra tela, bloqueio durante o envio) e o pet nas três listas.
- Os testes antigos de agendar ganharam o pet (a reserva agora envia `petId`). Suíte do front: 306 testes; tipos, lint e build verdes.
- **Mutação verificada** (15 aplicadas; 14 derrubadas): sem escolha automática do único pet; confirmar sem pet; reserva sem `petId`; recusa de pet descartando o horário; foco perdido ao arquivar; espécie não conferida; envio duplo do formulário; agenda sem observações; contador contando arquivados; lista sem `includeArchived`; "Meus agendamentos" sem pet; lista de pets velha valendo depois da recusa; erro sem pedido de foco depois da lista refeita; linha sumida sem levar o foco ao título. **Sobrevive:** a linha que zera `wasConfirming` depois de arquivar com os arquivados à vista (provavelmente equivalente em jsdom, onde o efeito de foco e a atualização da lista terminam na mesma ordem).

## Limitações e o que falta
- **Não verifiquei em navegador real** (sem capturas): o comportamento está coberto por testes em jsdom; aparência e uso em celular ficam para a etapa de redesign e de testes ponta a ponta.
- A visão semanal do prestador ainda não tem tela (só a API).
- Sem busca nem paginação de pets (o limite de 20 ativos e 100 no total torna a lista pequena).

## Perguntas de entrevista
1. Por que a tela exige o pet antes de a API exigir? O que daria errado se a API passasse a exigir primeiro?
2. Se a reserva é recusada porque o pet foi arquivado em outra aba, por que a tela mantém o horário em vez de recomeçar?
3. Onde deve morar a mensagem de erro de uma ação sobre uma linha que pode desaparecer, e por quê?
