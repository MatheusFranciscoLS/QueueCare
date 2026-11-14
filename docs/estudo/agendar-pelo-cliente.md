# Estudo: agendar pelo cliente (PR 24, parte B da reserva)

## Em uma frase
O cliente escolhe prestador, serviço, dia e horário livre, confirma, e a tela trata com clareza o caso de outra pessoa ter pegado o horário primeiro.

## O que existe
- Tela **Agendar** (só CUSTOMER) com cinco passos; menu e atalho no início.
- Horários do negócio no fuso do negócio; dia limitado a hoje até 90 dias à frente (fuso do negócio).
- Reserva com o horário local do negócio; confirmação com serviço, negócio, dia e hora.
- Mensagens para 409 e para as recusas 422 (passado, antecedência, fora do expediente, horário de verão) e 404 (serviço ou prestador que sumiram).

## Como ler o código
1. `api/booking.ts` (chamadas) e `api/messages.ts` (motivos).
2. `pages/customer/BookPage.tsx` (um componente por passo).

## Provas por teste (166 no front)
- Passos em sequência e descarte das escolhas seguintes ao trocar o prestador; prestador sem serviços.
- Dia sem horários, navegação pelos dias, limite de hoje e de 90 dias; a lista de outro dia nunca aparece sob o dia novo (carregando).
- Falha ao atualizar horários mantém a lista com aviso.
- Reservar: horário local enviado, confirmação, "agendar outro horário"; **409**: motivo, escolha descartada e lista refeita (mutação sem a chave de versão falha); cada recusa 422 com a sua mensagem; 404; duplo clique não duplica a reserva; "escolher outro" não envia nada.
- Perfis: só o cliente vê o menu e a tela; os outros voltam ao início sem nenhuma chamada ao catálogo.

## Três perguntas de entrevista
1. **O que acontece quando duas pessoas escolhem o mesmo horário?** A API (restrição no banco) aceita uma e recusa a outra com 409; a tela mostra o motivo e atualiza a lista.
2. **Por que enviar o horário local e não UTC?** A API resolve o horário local no fuso do negócio, tratando horário inexistente ou ambíguo de verão; o front não deve repetir essa regra.
3. **Por que os limites de data usam o fuso do negócio?** "Hoje" é o dia do negócio, que pode ser diferente do dia do aparelho.

## Limitações e o que falta
- Meus agendamentos e cancelamento pelo cliente (próximo PR); sem busca de prestadores (só os 50 primeiros, com aviso).
