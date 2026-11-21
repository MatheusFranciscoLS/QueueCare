# ADR 0021: reter clientes e reduzir faltas (política visível, contato do estúdio, "faltou" e lembrete)

- **Status:** Accepted (o Matheus delegou em 21/11/2026: "faça tudo de forma autônoma… pense num projeto real"; a motivação é dele: "não quero perder clientes")
- **Data:** 21/11/2026
- **Esforço:** ALTO no conjunto (cada fatia é um PR separado)

## Contexto: a análise crítica
Falta de cliente é o maior vazamento de receita de um estúdio de banho e tosa: o horário some e ninguém o reaproveita. Mas **travar o cancelamento ou exigir pagamento de todos** afasta justamente o cliente novo, que compara com o estúdio da esquina. Hoje o produto já tem prazo de cancelamento (o tutor não cancela nem remarca dentro do prazo) e 3 remarcações por agendamento, mas:

| Falha | Efeito |
|---|---|
| O tutor só descobre o prazo **depois de errar** (mensagem de recusa) | Sente que foi pego de surpresa e culpa o estúdio |
| Passou do prazo, o botão simplesmente some ou falha | Beco sem saída: o tutor não avisa, e some |
| Não existe "faltou" | Quem não aparece fica "chegou" para sempre; o estúdio não sabe quem falta sempre |
| Não há lembrete | O motivo mais comum de falta é esquecimento, e custa um clique evitar |

## Decisão
Três fatias, cada uma um PR. **Sem pagamento online e sem multa automática** (custo zero e sem cartão, ADR 0013): a política vale como combinado, e o app registra, mostra e avisa.

1. **Política visível e contato do estúdio.**
   - `providers.phone` (opcional, mesmo formato do telefone do tutor: só dígitos com DDI, `CHECK` no banco), editado em "Meu negócio".
   - O catálogo e a resposta do agendamento ganham o prazo e o telefone do estúdio; o agendamento ganha `freeChangeUntil` (início menos o prazo; só enquanto `SCHEDULED`).
   - A confirmação do Agendar diz "Cancelar ou remarcar de graça até ...". Em Meus agendamentos aparece o mesmo limite; **passado o limite, o botão vira "Falar com o estúdio"** (WhatsApp com mensagem pronta), nunca um beco sem saída. A regra do servidor não muda (continua 422 `TOO_LATE`).
   - Negócio novo sugere **12 h** de prazo (zero deixaria o estúdio sem proteção nenhuma) e a dica do campo mostra o prazo em horas. O passo "Meu negócio" do Início só fica pronto com o WhatsApp do estúdio informado, porque é por ele que o tutor fala depois do prazo.
2. **Estado "Faltou".** Novo `NO_SHOW`, só pela equipe, só a partir do horário marcado e só para `SCHEDULED`; evento `NO_SHOW` com quem registrou; estado final. A agenda mostra "N faltas" ao lado do tutor (faltas **naquele estúdio**, nunca de outros). O tutor vê "Faltou" em Meus agendamentos.
3. **Lembrete e confirmação pelo WhatsApp.** Botão "Lembrar" na agenda para os agendamentos das próximas 48 h: abre o WhatsApp com mensagem pronta (estúdio, pet, serviço, horário, prazo e pedido de resposta) e grava `reminded_at`, para a equipe não lembrar duas vezes. Sem envio automático (custo zero).

## Fora de escopo agora (próximos ADRs, só depois de ver o número de faltas)
- Lista de espera para reaproveitar horário cancelado.
- Sinal por Pix **manual** (chave do estúdio e "sinal recebido" marcado pela equipe), só para tutor novo, serviço longo ou 2 ou mais faltas. Nunca para todos.

## Regras
- Autorização na API: telefone e prazo do estúdio são dado do negócio (visíveis a quem agenda); a contagem de faltas e o lembrete são **só da equipe do próprio estúdio**; outro prestador, outro cliente e ADMIN não recebem.
- Telefone é dado pessoal: nunca em log nem em mensagem de erro; erro de formato não repete o valor.
- Cada PR atualiza o OpenAPI (ADR 0012) e traz teste de autorização do campo novo.

## Consequências
- O tutor entende a regra antes de errar, e o atraso vira conversa, não perda do cliente.
- O estúdio passa a ter dado real de faltas para decidir uma política mais firme depois.

## Andamento
- **Fatia 1** (PR 79): política visível e WhatsApp do estúdio. Mesclada em 21/11/2026.
- **Fatia 2** (PR 80): "Faltou". `NO_SHOW` é estado final e **continua ocupando o horário no banco** (a restrição de exclusão só ignora `CANCELLED`): o horário já passou ou está passando, então nada novo cabe nele, e evitar mexer na restrição de exclusão mantém o risco baixo. Limitação conhecida: se a pessoa faltar no começo de um atendimento longo, o resto do horário não volta para a agenda do dia. A contagem de faltas vem só na agenda do dia (como o telefone) e só do próprio estúdio.
