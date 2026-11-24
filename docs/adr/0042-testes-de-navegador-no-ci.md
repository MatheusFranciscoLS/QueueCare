# ADR 0042: testes de navegador no CI

- **Status:** Accepted (implementado em 24/11/2026)
- **Data:** 24/11/2026
- **Esforço:** BAIXO (um job, dois ajustes de configuração e um conserto de teste)

## Contexto
O projeto tem testes de navegador real (Playwright, `frontend/e2e/`): a API de verdade com PostgreSQL e dados fictícios, o site, e um navegador percorrendo os fluxos de cada perfil. Os testes unitários e de integração provam cada peça; estes provam que **as peças juntas** fazem o que o estúdio faz. Mas o `playwright.config.ts` dizia, desde o início, "não roda no CI do GitHub": só rodavam na máquina de quem desenvolve e só quando alguém lembrava.

O custo disso apareceu no mesmo dia em que a decisão foi tomada: **a demonstração pública (ADR 0041) quebrou o teste do app instalável (`pwa.spec.ts`) e nada avisou.** Os botões "Entrar como dona do estúdio" etc. fizeram `getByRole('button', { name: 'Entrar' })` casar com quatro botões, e o teste falhava com "strict mode violation". Foi achado ao rodar o conjunto pela primeira vez no modo do CI, não por um alerta.

## Decisão
**Um job `e2e` no CI** (`.github/workflows/ci.yml`), em paralelo aos demais, que:
- usa o **Chromium baixado pelo próprio Playwright** (a versão que a dependência pede, guardada em cache por versão); no computador de quem desenvolve continua o Edge instalado (o `playwright.config.ts` escolhe pela variável `CI`);
- sobe a API de desenvolvimento (PostgreSQL embutido, dados fictícios, **nenhuma dependência de produção nem de segredo**) e o site, pelo `webServer` do Playwright;
- constrói e serve o **build de produção** para o teste do app instalável e offline (o service worker só existe no build);
- roda `npm run e2e:ci`: **19 testes em duas execuções** (as telas que só leem, depois os fluxos), **80 s** com a API já compilada;
- usa **1 worker**, `forbidOnly` (um `test.only` esquecido não passa), **1 nova tentativa** e **rastro só de quem falha**, e guarda o relatório HTML como artefato **somente quando algo falha** (7 dias).

### Por que duas execuções
Os testes compartilham a mesma API e o mesmo banco. O teste "o dono gera a senha temporária da tutora" **altera a conta da tutora** (ela passa a exigir nova senha), e a spec da gaveta do celular entra com essa mesma conta: rodando na ordem alfabética (`fluxos` antes de `gaveta`), a gaveta esperava para sempre um botão que nunca aparecia. Em vez de reescrever os testes, o script roda primeiro as specs que não alteram nada e depois `fluxos`, **cada uma com API e banco novos**. Isto foi descoberto rodando o conjunto inteiro; sozinhas, as duas passavam.

### O que fica de fora
As specs `capturas`, `verificacoes` e `verificador` (capturas de tela, axe e medidas) continuam sendo a verificação visual de cada PR de tela, na máquina local: são lentas, geram arquivos e não afirmam comportamento. `fontes` e `medir-*` também.

## Retries e testes instáveis
O retry existe para absorver uma falha passageira do ambiente, **não para esconder um teste instável**. O Playwright marca como "flaky" o teste que falhou na primeira tentativa e passou na segunda, e o relatório lista. Regra: se o mesmo teste aparecer como flaky em mais de uma execução, ele é consertado ou removido, não tolerado.

## Custo
Zero: o repositório usa os runners padrão do GitHub Actions. O job leva alguns minutos (instalar Chromium na primeira vez, compilar a API de desenvolvimento e rodar 80 s de testes); `concurrency` já cancela execuções antigas do mesmo PR. O tempo real do job fica nos logs de cada execução e foi conferido nas primeiras (veja a seção abaixo).

## O que foi descartado
- **Rodar contra a produção:** criaria dados na demonstração pública compartilhada e dependeria do estado do Render. O ambiente do teste é descartável e isolado.
- **Subir um banco por serviço do Actions:** a API de desenvolvimento já usa o mesmo PostgreSQL embutido dos testes de integração, sem Docker.
- **Reescrever os testes para serem independentes de ordem:** é o certo no longo prazo, mas é uma mudança maior em testes que já provam o comportamento; a divisão em duas execuções dá o mesmo efeito agora.
- **Navegadores múltiplos (Firefox, WebKit):** o produto é uma PWA usada em Chrome/Edge/Safari; um segundo navegador dobra o tempo sem uma pergunta concreta a responder.

## Resultado das primeiras execuções
Medido nas duas primeiras execuções verdes (no PR e na main, 24/11/2026): o job inteiro levou **2 min 12 s** e **2 min 24 s** (instalar o Chromium, compilar a API de desenvolvimento e rodar os testes), sempre em paralelo aos demais jobs, que levam de 6 s a 2 min 30 s. Os testes em si: **11 em 54 s** e **8 em 42 s**. **Nenhum teste apareceu como "flaky"** (nenhum precisou da segunda tentativa). A regra acima continua valendo: se algum aparecer, é consertado.
