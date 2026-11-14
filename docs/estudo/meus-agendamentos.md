# Estudo: meus agendamentos e cancelamento pelo cliente (PR 25, parte C da reserva)

## Em uma frase
O cliente vê os próprios agendamentos (data e hora do negócio) e cancela com confirmação, e a tela trata com clareza prazo vencido e mudanças simultâneas.

## O que existe
- Tela **Meus agendamentos** (só CUSTOMER), menu e atalho no início.
- Cada item: dia por extenso e horário no fuso do negócio, serviço, negócio e situação (Agendado, Chegou, Concluído, Cancelado).
- Cancelar: botão, confirmação, envio único; sucesso marca como Cancelado na hora e avisa.

## Como ler o código
1.  ( e ) e  (motivos).
2.  (lista e linha com a lógica de cancelar).

## Provas por teste (180 no front)
- Data/hora no fuso do negócio (02:00Z ainda é o dia anterior em São Paulo); lista vazia; falha ao carregar; perfis sem acesso.
- Confirmação e Voltar; sucesso sem depender do GET seguinte (mutação removendo isso falha); duplo clique envia um cancelamento.
- Cada recusa com a sua mensagem; as três em que o servidor já mudou refazem a lista (mutação sem o refetch falha); prazo vencido não refaz.

## Três perguntas de entrevista
1. **Por que refazer a lista em ALREADY_CANCELLED ou CONCURRENT_UPDATE?** Porque o erro revela que o estado mudou; só mostrar a mensagem deixaria o botão de cancelar errado na tela.
2. **Por que o TOO_LATE não refaz a lista?** Nada mudou no servidor: o agendamento continua agendado, só não pode mais ser cancelado.
3. **Quem decide se pode cancelar?** A API (prazo do prestador, dono do agendamento); a tela apenas mostra o botão para itens agendados e traduz a recusa.

## Limitações e o que falta
- Sem paginação além dos 50 mais recentes; sem filtro por situação.
- Check-in e fila (M4) ainda não existem.
