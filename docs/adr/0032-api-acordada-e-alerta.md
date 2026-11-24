# ADR 0032: API acordada no horário de atendimento e alerta de queda

- **Status:** Accepted (implementado em 23/11/2026)
- **Data:** 23/11/2026
- **Esforço:** BAIXO (um workflow, nenhuma linha de código da aplicação)

## Contexto: a análise crítica
Duas coisas do uso real que o custo zero deixa de fora:

1. **A API dorme.** No plano gratuito do Render ela suspende depois de ~15 min sem requisição e leva cerca de um minuto para acordar. O tutor que abre o app de manhã ou depois de um intervalo espera, e muita gente fecha a aba antes. O `WakingBanner` explica a espera, mas não a elimina.
2. **Ninguém avisa se cair.** Em 23/11/2026 descobrimos uma API parada havia dias porque alguém olhou. Um estúdio não pode depender disso.

## Decisão
1. **Um workflow agendado** (`.github/workflows/monitor.yml`) no GitHub Actions, que é gratuito em repositório público e não pede conta nem cartão.
2. **Ping leve a cada 10 min, só no horário de atendimento** (08h às 19h50 de Brasília, segunda a sábado), na rota de **vivacidade**, que não toca no banco.
3. **Roteiro completo uma vez por dia** (`ops/smoke.sh`: API, banco, código de requisição, rota protegida e site).
4. **O alerta é o próprio fracasso do workflow**: o GitHub manda e-mail de execução agendada que falhou. Cada chamada tem tentativas (`ops/retry.sh`), então uma oscilação curta não alarma.

### Por que só no horário de atendimento (a decisão que importa)
- O Render gratuito dá **750 horas por mês para a conta inteira**, e há outros serviços nela. Manter a QueueCare acordada 24 h por dia gastaria ~744 h sozinha e poderia **suspender todos** no fim do mês. Com o horário comercial são ~310 h.
- O ping **não toca no banco** de propósito: o Neon gratuito tem cota de CU-horas e suspende após 5 min sem uso; mantê-lo acordado o dia todo consumiria a cota. O banco acorda sozinho na primeira requisição real (segundos).

## O que foi descartado
- **Ping 24 h por dia:** estoura a cota de horas da conta (acima).
- **Serviço externo de monitoramento (UptimeRobot e similares):** exige conta de terceiro e é mais uma coisa para o dono lembrar; pode ser adicionado depois sem mudar nada aqui, porque `ops/smoke.sh` já serve de base.
- **Plano pago do Render:** resolve de vez (a API não dorme, com recuperação melhor), mas quebra a regra de custo zero (ADR 0013). É o caminho certo quando o estúdio real começar a pagar pelo sistema.
- **Alerta por WhatsApp ou SMS:** é envio automático de mensagem, vetado pelo ADR 0013.

## Riscos conhecidos
- **Execução agendada do GitHub não é pontual:** pode atrasar minutos ou pular. Por isso é "esforço honesto": na maioria dos intervalos a API fica acordada, mas pode haver um primeiro acesso lento.
- **Durante uma queda longa chegam vários e-mails** (um por execução com falha). É ruído aceitável: uma queda em horário de atendimento merece atenção.
- **O GitHub desativa agendamentos de repositório sem atividade por 60 dias.** Há atividade constante; se o projeto parar, o monitor para junto (e a aba *Actions* avisa).
- **O e-mail vai para quem editou o agendamento pela última vez**, não para uma lista. Para quem opera o estúdio receber também, precisa de um destino próprio (e-mail transacional, fora do custo zero).

## Consequências
- Na maior parte do expediente a API já está acordada quando o tutor chega.
- Uma queda deixa de depender de alguém olhar: aparece como execução vermelha e e-mail.
- Custo: zero, e o consumo de horas do Render e do Neon fica sob controle por desenho.
