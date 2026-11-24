# ADR 0037: acordar a API assim que o site abre

- **Status:** Accepted (implementado em 24/11/2026)
- **Data:** 24/11/2026
- **Esforço:** BAIXO (uma função de 3 linhas úteis e 3 testes)

## Contexto: a medição que mudou o plano
A API roda no Render gratuito, que a coloca para dormir depois de ~15 min sem uso. Eu havia estimado "cerca de 1 minuto" para acordar. **Medido em 24/11/2026, em plena manhã de dia útil: a primeira chamada levou 115 s** (200 OK, as seguintes 0,3 s). Para um visitante de portfólio, dois minutos olhando uma tela de espera é o fim da visita.

O ADR 0032 tentou resolver isso com um monitor agendado no GitHub Actions. **Não funcionou como previsto**: em 15 horas o agendamento de 10 em 10 minutos **nunca rodou** (só a minha execução manual), e o backup diário, também agendado e marcado para 06:17 UTC, rodou às 14:33 UTC num dia e às 18:04 UTC no outro. O GitHub atrasa por horas, ou pula, execuções agendadas de repositórios de baixa atividade. Um aquecimento que depende disso não é confiável.

## Decisão
**Acordar a API pelo próprio site**, que é o único gatilho que acompanha exatamente quem vai usar: ao abrir o site, o navegador faz **uma** chamada à rota de **prontidão** da API (`/actuator/health/readiness`), sem esperar a pessoa clicar em nada. O relógio da espera passa a correr enquanto ela lê a página e digita o e-mail, em vez de começar no clique em Entrar. Quem abre o site e se demora meio minuto já entra com a espera pela metade; quem volta em seguida, com a API acordada, não espera.

Detalhes que importam:
- **Sem credencial, sem dado e sem ler a resposta** (`mode: 'no-cors'`, `credentials: 'omit'`): não depende de permissão de origem, não leva token e a resposta opaca nem é usada. **A prontidão consulta o banco, de propósito:** o Neon gratuito também dorme (suspende após 5 min sem uso) e acordá-lo leva ~7 s (medido: o primeiro login com a API já acordada levou 7 017 ms, no log de acesso do ADR 0036). Acordando junto, o banco já está pronto quando a pessoa clica em Entrar. Custo: cada visita mantém o banco acordado por ~5 min, cerca de 0,02 CU-h (0,25 CU x 5 min) contra os 100 CU-h mensais do plano, ou seja, milhares de visitas. **Pingadores automáticos e frequentes (ADR 0032, `docs/operacao.md`) continuam usando só a vivacidade**: com aquela frequência, o banco ficaria acordado o dia todo.
- **Falha em silêncio.** O objetivo é só acordar o servidor; qualquer erro de verdade aparece na primeira chamada real, com o aviso de "servidor acordando" que já existia.
- **Só em produção** (quando `VITE_API_URL` está definida): em desenvolvimento a API local não dorme.
- **Custo zero e sem agendamento**: só acorda quando alguém aparece, então não gasta as 750 horas mensais do Render à toa (que são da conta inteira, e há outros projetos nela).

## O que foi descartado
- **Manter só o monitor do GitHub Actions:** não roda de forma confiável (evidência acima). Continua no repositório como verificação manual e como tentativa extra, sem ninguém depender dele.
- **Pingar 24 h por dia por um serviço externo:** estoura as 750 horas gratuitas da conta do Render e poderia suspender os outros projetos. Se o dono quiser um serviço externo, deve ser **restrito ao horário comercial** (veja `docs/operacao.md`).
- **Reduzir o tempo de partida do Spring Boot** (inicialização preguiçosa, AOT): arrisca esconder erros de inicialização ou fixar condições de configuração. **Feito depois, de forma segura, com AppCDS** ([ADR 0040](0040-partida-mais-rapida-com-appcds.md)): a partida caiu de ~99 s para 58 s, medida no Render.
- **Plano pago do Render:** resolve de vez e quebra o custo zero (ADR 0013).

## Riscos conhecidos
- **O primeiro visitante ainda espera** se abrir o site, clicar em Entrar na hora e a API dormia: o aquecimento reduz, não elimina. Para quem chega com a API já acordada (qualquer visita nos 15 minutos depois da anterior), não há espera.
- **Cada visita ao site acorda a API**, inclusive de robôs e de quem só olha: aceitável e é o objetivo.
- **Bloqueadores de conteúdo** podem impedir a chamada; nesse caso vale o comportamento de antes (a espera começa no primeiro clique).

## Consequências
- A espera mais comum, a de quem abre o site depois de um período parado, corre em paralelo ao tempo de leitura da página.
- A premissa "o GitHub Actions mantém a API acordada" fica corrigida no ADR 0032 e no `docs/operacao.md`.
