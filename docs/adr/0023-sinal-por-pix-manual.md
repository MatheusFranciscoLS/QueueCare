# ADR 0023: sinal por Pix, manual e só para quem faz sentido

- **Status:** Proposed (continuação dos ADRs 0021 e 0022; ligar só depois de o estúdio ter dados reais de faltas, para não afastar cliente à toa)
- **Data:** 21/11/2026
- **Esforço:** ALTO (campos novos no estúdio e no agendamento, regra de quando pedir, três telas)

## Contexto: a análise crítica
A ideia do dono ("pagamento antecipado") protege a agenda, mas **pedir pagamento de todo mundo** é o caminho mais rápido para perder o cliente novo: ele compara com o estúdio da esquina que não pede nada. Também não dá para cobrar online sem sair do custo zero e sem cartão (ADR 0013). O que o balcão faz na prática: pede um **sinal** de quem tem risco (cliente novo, serviço longo, quem já faltou), por Pix, e confirma o horário quando o comprovante chega.

## Decisão (proposta)
Sinal **manual**: o app mostra a chave Pix do estúdio e o valor; a equipe marca "sinal recebido". Nenhuma integração com banco, nenhuma cobrança automática, nenhuma multa.

1. **Estúdio configura** em "Meu negócio": chave Pix (texto livre, opcional), valor do sinal em centavos e **quando pedir** (nunca; só de tutor sem atendimento concluído neste estúdio; ou também de quem já tem 2 ou mais faltas **neste estúdio**, contagem do ADR 0021). Padrão: nunca (o estúdio liga quando quiser).
2. **O agendamento guarda** `deposit_cents` (congelado na reserva, como o preço) e `deposit_status` (`NONE`, `REQUESTED`, `RECEIVED`). A regra é avaliada **na reserva**, no servidor.
3. **O tutor vê antes de confirmar**: "Este estúdio pede um sinal de R$ X por Pix para segurar o horário. Chave: ... Envie o comprovante pelo WhatsApp do estúdio." Depois de reservar, Meus agendamentos mostra "Sinal pendente" ou "Sinal recebido".
4. **A equipe vê e marca**: selo "Sinal pendente" na Agenda e botão "Sinal recebido" (grava quem e quando, no histórico). O sinal **não cancela nada sozinho**: o horário não some se o sinal atrasar; quem decide é a equipe, e o lembrete (ADR 0021) é o canal.
5. **Valor do sinal** abate do preço no balcão (informativo: o app não cobra).

## Fora de escopo
- Pagamento dentro do app, estorno, multa automática, conciliação bancária, QR Code Pix dinâmico (precisa de integração).

## Autorização e privacidade
- A chave Pix é dado do estúdio, visível a quem agenda **só quando o sinal é exigido dele**; a regra "2 faltas" nunca expõe a contagem ao tutor, só o fato de que o sinal é pedido.
- Marcar "recebido" é só da equipe do estúdio; outro estúdio, tutor e ADMIN respondem como inexistente.

## Consequências
- O estúdio ganha uma proteção graduada, em vez de uma regra dura para todos.
- Mais um estado por agendamento e mais uma decisão para a equipe cuidar; por isso começa desligado.
