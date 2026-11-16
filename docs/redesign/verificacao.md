# Verificação em navegador real (ADR 0018)

Todo PR de tela do redesign é conferido **em navegador real**, com números, não só pelos testes de comportamento (jsdom). Isto descreve o ambiente e os comandos. Nada daqui vai para a produção.

## Ambiente local (sem Docker)
- **API de demonstração:** `npm run dev:api` (na pasta `frontend`). Sobe a API com **PostgreSQL embutido** (o mesmo dos testes) e **dados fictícios**: o estúdio "Estúdio Bem-te-vi" (endereço público `estudio-bem-te-vi`), a dona, um atendente, três clientes com pets, um cliente novo sem nada e agendamentos de hoje e dos próximos dias em todos os estados (concluído, chegou, agendado, cancelado). Código: `backend/src/test/java/com/queuecare/dev/LocalDevServer.java` (fica em `src/test`: nunca entra na imagem de produção). Na primeira vez precisa de rede para montar o classpath; depois funciona sem.
- **Contas** (todas com a mesma senha fictícia, formada pelas palavras Senha, Demo e 12345 unidas por hífen; ela é montada por partes no código para o varredor de segredos não a confundir com um segredo real): `admin@pelo.test` (administrador), `helena@pelo.test` (prestadora), `bruno@pelo.test` (atendente), `marina@pelo.test`, `joao@pelo.test`, `carla@pelo.test` (clientes) e `paulo@pelo.test` (cliente novo, para os estados vazios).
- **Front:** `npm run dev` (Vite, porta 5173). O Playwright sobe a API e o Vite sozinho se ainda não estiverem no ar.
- O navegador é o **Edge instalado** (sem baixar navegador).

## Comandos (pasta `frontend`)
| Comando | O que faz |
|---|---|
| `npm run e2e:capturas` | fotografa **todas as telas** em **1280 e 390 px**, nos temas **claro e escuro** (68 imagens em `e2e/saida/capturas/`); `QC_TELAS=agenda,fila` filtra |
| `npm run e2e:verificar` | por tela, tema e janela: **axe** (WCAG 2.2 AA, graves e críticas), **foco visível** em cada elemento (percurso por Tab), **rolagem horizontal** em 320/390/768/1024/1280 px e **alvos de toque** menores que 44 px; grava `e2e/saida/relatorio.json` |
| `QC_STRICT=1 npm run e2e:verificar` | o mesmo, mas **qualquer violação reprova**: é como cada PR de tela é conferido |
| `npm run e2e:peso` | build de produção e **peso real transferido por rota** (gzip -9) por tipo (JS, CSS, fontes) |

A saída (`e2e/saida/`) não vai para o repositório. As capturas são o que o Matheus (e o ChatGPT) aprovam a cada PR.

## Linha de base do produto ANTES do redesign (16/11/2026)
Medida em Edge, 17 telas x 2 janelas x 2 temas = 68 combinações:
- **axe, violações graves ou críticas: 0.**
- **Foco sem contorno visível: 120** (campos de data e de horário da agenda e do expediente, entre outros): o foco existe, mas não se enxerga.
- **Rolagem horizontal a 320 px** em todas as telas do prestador e do administrador (e a 390 px nas telas do administrador): 44 larguras no total.
- **Alvos de toque menores que 44 px: 0.**
- **Peso, primeira visita a `/entrar`: 183,4 kB** (JS 103,2, CSS 6,8, fontes 73,1, outros 0,3). Há um único pacote de JS: nada é carregado a mais depois de entrar nem ao abrir a agenda.

Esses números são o ponto de partida: o redesign precisa terminar com **zero** nos três primeiros itens e dentro das metas de peso fixadas no ADR 0018.

## Limites conhecidos
- O token da sessão fica só em memória (decisão de segurança do ADR 0003): os roteiros entram pela tela de login e navegam **pelo menu**, nunca recarregando a página.
- Esta verificação **não roda no CI do GitHub** (o CI roda os testes de comportamento, a imagem Docker e a varredura de segredos): ela depende do Edge instalado e do ambiente local. Quando a suíte E2E crítica for consolidada (R12), a parte estável dela entra no CI.
