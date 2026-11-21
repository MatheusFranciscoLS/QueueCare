# ADR 0031: resumo do estúdio

- **Status:** Accepted (implementado em 21/11/2026)
- **Data:** 21/11/2026
- **Esforço:** MÉDIO (um serviço de leitura, duas rotas, uma tela, 6 testes de integração e 7 de tela)

## Contexto: a análise crítica
O dono do estúdio responde perguntas simples todo fim de dia e todo fim de semana: quantos atendi, quantos faltaram, qual serviço sai mais, quem sumiu. Sem uma tela para isso, ele soma de memória ou no caderno. A tentação é construir um "financeiro" (caixa, contas, faturamento, DRE), mas o app **não sabe o que foi pago no balcão** nem os descontos: um número chamado "faturamento" seria uma mentira confiante.

## Decisão
1. **Uma tela "Resumo"** (`/resumo`), só do **dono**: tem valores em dinheiro e nome de cliente, então o atendente não a vê (rota, menu e API). Períodos: Hoje, 7 dias (com hoje incluso), 30 dias e Este mês, em **dias do estúdio**.
2. **Só lê o que já existe.** Nenhuma tabela nova nem dado novo gravado; `GET /api/provider/reports/summary?from&to` e `/lapsed?days`, sem cache (o número muda a cada atendimento).
3. **Valores com nome honesto, nunca "faturamento":**
   - *Atendimentos concluídos*: soma do valor de tabela (congelado na reserva) dos concluídos;
   - *Ainda por concluir (previsto)*: marcados e em andamento;
   - *Sinais Pix recebidos*: o que a equipe marcou como recebido.
   Falta e cancelamento **nunca** entram em valor, e a tela diz em texto que **não são faturamento**.
4. **O dia é o do fuso do estúdio**, não o do servidor: um atendimento às 23h30 locais cai no dia local, mesmo sendo o dia seguinte em UTC (há teste para isso). A série por dia **não pula dias** sem atendimento.
5. **Por serviço, por dia e quem mais faltou** (até 5): por serviço só com concluídos; por dia como gráfico simples (até 31 dias) **mais** a tabela completa (o gráfico é decorativo para leitor de tela e tem legenda); faltas por tutor para o dono avaliar a política (ADR 0021).
6. **Clientes que sumiram**: já foram atendidos (concluído) neste estúdio, o último atendimento foi há mais de N dias (30, 60 ou 90; a API aceita de 14 a 730) e **não têm horário marcado**. Só tutor ativo (conta excluída ou inativa não aparece). A equipe chama pelo WhatsApp com a mensagem pronta (o app só prepara o texto, como no lembrete). É o primeiro passo do "na hora de voltar", sem exigir que o estúdio defina intervalo por pet.
7. **Limites e validação:** o período termina depois de começar e tem até 366 dias; a lista de "sumiram" traz até 30 clientes, dos que sumiram há menos tempo para os que sumiram há mais.

## O que foi descartado
- **"Faturamento", caixa, despesas, DRE:** o app não tem como saber. Registrar pagamentos é outro produto (e o ADR 0023 manteve o Pix como manual de propósito).
- **Comparar com o período anterior, projeções, metas:** mais números sem decisão atrás; fica para quando o dono pedir.
- **Exportar para planilha:** útil, mas é uma rota a mais com dados pessoais saindo do app; entra junto com a importação, se houver demanda.
- **Resumo para o atendente:** valores em dinheiro e nomes de clientes são do dono.

## Consequências
- O dono sabe em segundos como foi a semana e quem chamar de volta, sem sair do app.
- Os números só valem tanto quanto o uso: um atendimento nunca marcado como concluído não aparece como concluído. O resumo é um espelho da operação, não uma fonte independente.
- O cálculo roda em SQL agregado no banco (um par de consultas por período), sem carregar agendamentos em memória.
