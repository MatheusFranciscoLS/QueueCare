# ADR 0022: lista de espera para reaproveitar horário que vagou

- **Status:** Accepted (continuação do ADR 0021; implementado em 21/11/2026 dentro da autonomia dada pelo Matheus: "finalize todo o projeto e o que temos em mente")
- **Data:** 21/11/2026
- **Esforço:** ALTO (tabela nova, quatro endpoints, duas telas)

## Contexto: a análise crítica
O ADR 0021 reduz a chance de o horário se perder (política visível, lembrete) e registra quem falta. Mas quando alguém **cancela dentro do prazo** ou o estúdio fica com a agenda cheia de um lado e vazia de outro, o horário que vagou continua sendo receita perdida: o tutor que queria aquele dia já foi embora. Hoje, para o tutor, "nenhum horário livre neste dia" é um beco sem saída.

## Decisão (proposta)
Lista de espera **conduzida pela equipe**, sem envio automático (custo zero, ADR 0013), no mesmo espírito do lembrete: o app junta as pessoas certas e a equipe chama pelo WhatsApp.

1. **Tutor entra na lista** quando o Agendar não tem horário no dia: "Avise-me se vagar um horário neste dia" (estúdio, serviço, **pet** e dia escolhidos). Uma entrada ativa por tutor, estúdio, serviço, pet e dia (o banco garante com índice único parcial). Limite de 10 entradas ativas por tutor.
2. **Tutor vê e sai da lista** em "Meus agendamentos" (seção "Lista de espera"), com o dia, o serviço e o pet, e remove quando quiser. Uma entrada some sozinha no dia seguinte ao dia pedido.
3. **Equipe vê quem espera** na Agenda do dia ("Lista de espera: N"), por ordem de entrada: tutor, pet, serviço, duração prevista **para aquele pet** (ADR 0020) e o WhatsApp com mensagem pronta ("vagou um horário às ..."). A equipe marca "chamado" (grava quando) ou remove. Quando o tutor agenda, a entrada correspondente fecha sozinha.
4. **Sem reserva automática e sem prioridade garantida**: quem chamar primeiro e for respondido primeiro agenda. A lista só informa a ordem de entrada.

## Autorização e privacidade
- Telefone e dados do tutor só chegam à equipe **do estúdio da lista**; o tutor só enxerga as próprias entradas; entrada de outro tutor ou de outro estúdio responde como inexistente (404), sem pista.
- A entrada guarda só ids (estúdio, serviço, tutor, pet) e a data pedida; a equipe lê o nome do pet e do tutor pelos dados atuais, com as mesmas regras da agenda.

## Fora de escopo
- Notificação automática (e-mail, SMS ou WhatsApp API), reserva automática ao vagar, prioridade por fidelidade, lista por intervalo de dias.

## Consequências
- "Sem horário" deixa de ser o fim da conversa para o tutor, e o estúdio reaproveita horários sem custo.
- Mais uma lista para a equipe cuidar: por isso fica visível só na Agenda do dia e some sozinha.

## Andamento
Implementado em 21/11/2026 (V20, `WaitlistService`, `/api/waitlist`): o tutor entra pelo Agendar quando o dia está cheio, vê e sai em Meus agendamentos, e a equipe vê a lista na Agenda do dia (com a duração prevista para cada pet), chama pelo WhatsApp e tira. Quem já tem agendamento ativo no estúdio naquele dia **sai da lista sozinho** (a lista deixa de mostrar; a entrada continua guardada, e volta a aparecer se o agendamento for cancelado ou o tutor faltar). Limite de 10 entradas ativas por tutor. Sem prazo de validade explícito: a entrada some da tela do tutor quando o dia passa.
