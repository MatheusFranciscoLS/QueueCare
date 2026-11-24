# Funcionalidades

O detalhe do que o QueueCare faz, para quem quer ir além do resumo do [README](../README.md). Movido do README para ele abrir pelo que importa.

## O que o sistema faz
**Para o tutor (cliente):** cadastra os pets (porte, pelo, peso, comportamento, saúde, vacinas), agenda escolhendo serviço, pet, dia e horário (a duração e o valor seguem o porte e o pelo do pet; o app já mostra o primeiro dia com vaga), vê **até quando cancela ou remarca de graça** antes de confirmar e, passado o prazo, fala com o estúdio pelo WhatsApp; acompanha o atendimento (chegou, em atendimento, pronto para retirada), vê o histórico de cada pet e entra na **lista de espera** quando o dia está cheio.

**Para o estúdio (prestador e atendentes):** agenda do dia e da semana, fila com os estados do atendimento, ficha do pet na frente de quem atende (com aviso de comportamento), WhatsApp do tutor com mensagem pronta (pet pronto, **lembrete** do dia seguinte, chamada da lista de espera), registro de **faltas** com contador por tutor, serviços com preço e duração por porte e pelo, expediente, equipe, página pública e política de cancelamento configurável.

**Administração:** criação e manutenção de contas.

### Pronto para uso real (ADRs 0024 a 0033)
O que separa uma demonstração de um sistema que um estúdio usa todo dia:

| Frente | O que existe |
|---|---|
| **Operação** | [Folgas, feriados e férias](adr/0024-folgas-feriados-e-ferias.md) (o tutor não agenda em dia fechado); [Clientes](adr/0025-clientes-do-estudio-e-senha-temporaria.md) (busca no balcão e senha temporária gerada pelo dono, sem e-mail); [cadastro e reserva pelo balcão](adr/0033-cadastro-e-reserva-pelo-balcao.md) (a equipe agenda por quem liga ou chama no WhatsApp); [desfazer a última etapa](adr/0026-desfazer-etapa-do-atendimento.md) do atendimento; [Resumo do estúdio](adr/0031-resumo-do-estudio.md) (atendimentos, valores com nome honesto, faltas e clientes que sumiram) |
| **Confiança e dados** | [Política de Privacidade, baixar os próprios dados e excluir a conta por anonimização](adr/0027-lgpd-minima.md), com o [inventário de dados pessoais](privacidade-dados.md) |
| **Recuperação** | [Backup criptografado e restauração ensaiada a **cada PR**](adr/0028-backup-e-restauracao.md) (a CI restaura num segundo PostgreSQL e exige que seja idêntico), com o [passo a passo](backup.md) |
| **Operação e suporte** | [Código de requisição em cada resposta e em cada linha de log](adr/0029-identificador-de-requisicao-e-operacao.md), `ops/smoke.sh`, [API acordada no horário de atendimento e alerta de queda](adr/0032-api-acordada-e-alerta.md) e o [roteiro de deploy, rollback e incidentes](operacao.md) |
| **Uso no celular** | [App instalável que abre sem internet](adr/0030-app-instalavel-e-offline.md) (o service worker nunca guarda dados da API) |

Cada decisão tem um ADR com o que foi **descartado** e os **riscos conhecidos**, inclusive os limites do custo zero.

Sem pagamento online e sem envio automático de mensagens (custo zero, [ADR 0013](adr/0013-hospedagem-custo-zero.md)): o WhatsApp abre com a mensagem pronta e a equipe envia.

## Estado de implementação

Estado atual (21/11/2026): produto de uso diário implementado em fatias com ADR cada: [0019](adr/0019-uso-real-do-banho-e-tosa.md) (telefone, ficha do pet, preço, estados de atendimento, Início como painel, histórico do pet), [0020](adr/0020-duracao-por-porte-e-pelo.md) (duração por porte e pelo), [0021](adr/0021-retencao-de-clientes-e-faltas.md) (política visível, faltas e lembrete) e [0022](adr/0022-lista-de-espera.md) (lista de espera). Redesign completo ([ADR 0018](adr/0018-redesign.md)) com verificação em navegador real (acessibilidade, foco, rolagem e alvos de toque nas 84 combinações de tela, janela e tema). Próximo, proposto: [sinal por Pix manual (ADR 0023)](adr/0023-sinal-por-pix-manual.md), a ligar só depois de dados reais de faltas. Rumo ao uso real: [folgas, feriados e férias (ADR 0024)](adr/0024-folgas-feriados-e-ferias.md), [clientes do estúdio e senha temporária (ADR 0025)](adr/0025-clientes-do-estudio-e-senha-temporaria.md), [desfazer a última etapa do atendimento (ADR 0026)](adr/0026-desfazer-etapa-do-atendimento.md) , [LGPD mínima (ADR 0027)](adr/0027-lgpd-minima.md), com o [inventário de dados pessoais](privacidade-dados.md), [backup criptografado com restauração ensaiada a cada PR (ADR 0028)](adr/0028-backup-e-restauracao.md), com o [passo a passo](backup.md), e [código de requisição nos logs e roteiro de operação (ADR 0029)](adr/0029-identificador-de-requisicao-e-operacao.md), com o [roteiro de deploy, rollback e incidentes](operacao.md), , [app instalável que abre sem internet (ADR 0030)](adr/0030-app-instalavel-e-offline.md) e [resumo do estúdio, com clientes que sumiram (ADR 0031)](adr/0031-resumo-do-estudio.md).
