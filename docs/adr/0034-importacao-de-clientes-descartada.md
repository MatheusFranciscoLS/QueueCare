# ADR 0034: importação de clientes por planilha (DESCARTADA)

- **Status:** Rejected (implementada e revertida em 24/11/2026: PR 111 e a reversão dele)
- **Data:** 24/11/2026

## O que aconteceu
A importação de clientes por CSV era um item da lista de prioridades sugerida na revisão do ChatGPT. Foi implementada completa (leitor de CSV do Excel, painel de conferência, rota com limite, 22 testes) e **revertida no mesmo dia**, a pedido do dono do produto.

## Por que foi descartada
- **Nasceu de uma suposição, não de uma necessidade.** Presumiu-se que um estúdio tem uma planilha de clientes para trazer. Ninguém usou o sistema ainda, e não havia nenhuma lista real para importar.
- **O projeto é de portfólio.** Cada função precisa justificar o que mostra de engenharia e o que resolve para quem usa. Esta resolvia um problema hipotético, e a profundidade que ela demonstrava (leitura de CSV, Windows-1252, importação parcial) não era a que o projeto precisa mostrar.
- **Custo de manter.** Uma rota que cria contas em lote, com limite, auditoria e privacidade, é superfície a proteger e a testar para uma função que ninguém pediu.

## O que fica de lição
Antes de implementar um item de uma lista (inclusive uma sugerida por revisão externa), perguntar **quem pede isso e o que acontece hoje sem ele**. Se a resposta é uma suposição, perguntar ao dono antes, e não depois de entregar. O cadastro e a reserva pelo balcão (ADR 0033) continuam: partem de uma situação concreta (o cliente pede horário por WhatsApp) e foram decididos com o dono antes de serem feitos.

## Se um estúdio real pedir no futuro
O desenho está no histórico do PR 111 (leitura do CSV no navegador, sem upload; cada linha independente; nunca duplicar telefone; nunca adivinhar espécie; resposta sem dado pessoal). Reaproveitaria o cadastro do balcão, sem mudar nada nele.
