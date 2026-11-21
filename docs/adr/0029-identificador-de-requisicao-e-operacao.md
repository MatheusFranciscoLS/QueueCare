# ADR 0029: identificador de requisição e roteiro de operação

- **Status:** Accepted (implementado em 21/11/2026, sexto item do plano de prontidão para uso real)
- **Data:** 21/11/2026
- **Esforço:** PEQUENO a MÉDIO (um filtro, um padrão de log, três cabeçalhos de CORS, uma mensagem de tela, um script e um roteiro)

## Contexto: a análise crítica
Quando um tutor diz "deu erro", quem opera precisa descobrir **qual** chamada falhou e **por quê**. Sem um identificador, a única pista é o horário aproximado, e num log com várias pessoas usando ao mesmo tempo isso é procurar agulha. A infraestrutura gratuita (API que dorme, banco que dorme, logs de pouca retenção) piora: o log pode rolar antes de alguém olhar. E no dia de um incidente é tarde para escrever o roteiro de como se recupera.

## Decisão
1. **Código de requisição em todas as chamadas** (`RequestIdFilter`, ordem mais alta, antes da cadeia de segurança): cada requisição ganha um código curto (16 hexadecimais), colocado no **MDC** e devolvido no cabeçalho `X-Request-Id`. Cobre também 401, 403, 429, 404 e erros da própria segurança.
2. **Todo log leva o código**: `logging.pattern.level = "%5p [rid=%X{requestId:-}]"`. Uma busca por `rid=<código>` mostra todas as linhas daquela chamada, com a exceção e o rastro. Provado por teste: o log sai como `INFO [rid=meu-codigo-123] … linha de log DENTRO da chamada`.
3. **Código vindo do cliente só vale se for seguro**: 8 a 64 caracteres entre letras, dígitos, `.`, `-` e `_`. O resto é descartado e substituído, para ninguém injetar linhas falsas no log (quebra de linha, aspas) nem cabeçalhos na resposta. O **MDC é sempre limpo** no fim, então a thread volta ao pool sem o código da chamada anterior.
4. **CORS expõe `X-Request-Id` e `Retry-After`.** Sem isso o navegador de outra origem (o site na Vercel falando com a API no Render) **não consegue ler** esses cabeçalhos. Isto também corrige, sem alarde, o tempo de espera do erro 429, que a tela já tentava ler e na prática nunca recebia em produção.
5. **A tela mostra o código num erro do servidor**: "Algo deu errado no servidor. Tente novamente em instantes. Se continuar, informe o código a1b2c3d4e5f60718." Erros que a pessoa resolve sozinha (senha errada, campo inválido, 404) não ganham código: seria ruído.
6. **`ops/smoke.sh`**: verificação de cinco passos (liveness, readiness com banco, código de requisição, 401 numa rota protegida, site) que diz **qual** camada falhou e serve de base para um monitor externo. Foi provado com um teste negativo (contra uma versão sem o filtro, falha dizendo exatamente isso) e um positivo.
7. **`docs/operacao.md`**: deploy, **regra das migrações** (expandir e contrair em deploys separados, porque o Flyway só anda para frente), rollback por tipo de problema, checklist de incidente de 5 minutos, contingência com o estúdio aberto e onde ficam os segredos.

## O que foi descartado
- **Rastreamento distribuído completo (OpenTelemetry, Jaeger)**: o app é um monólito; um código no log resolve a dor real a custo zero.
- **Serviço de erros de terceiros (Sentry e similares)**: envia dados de erro para fora (ADR 0027) e tem custo acima do plano gratuito.
- **Colocar o código no corpo do erro de cada rota**: cada tratador de exceção teria de lembrar de fazê-lo; o cabeçalho cobre todos de uma vez, inclusive os que a segurança responde antes do controlador.

## Consequências
- "Deu erro" vira "o código é X": a causa está a uma busca de distância, enquanto o log ainda existir.
- O monitor externo e o `smoke.sh` dão ao dono um jeito de saber que caiu antes do tutor.
- **Limite honesto:** o Render gratuito guarda pouco histórico de log. O código ajuda a achar a linha, não a recuperar um log que já rolou. Guardar logs por mais tempo exige um serviço pago ou um destino de logs externo, fora do custo zero.
- O código de requisição **não é** um segredo nem identifica a pessoa: é aleatório e vale para uma chamada só.
