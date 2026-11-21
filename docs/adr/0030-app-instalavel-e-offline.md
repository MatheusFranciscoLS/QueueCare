# ADR 0030: app instalável (PWA) que abre sem internet

- **Status:** Accepted (implementado em 21/11/2026)
- **Data:** 21/11/2026
- **Esforço:** PEQUENO a MÉDIO (manifesto, ícones, um service worker, dois componentes, testes e uma prova no navegador)

## Contexto: a análise crítica
O estúdio usa o app no balcão, de pé, no celular. Um site que precisa ser aberto pelo navegador, digitando o endereço, é fricção demais para uso diário; e a internet de um estúdio cai. O app nativo (Android/iOS) custaria duas bases de código, lojas e manutenção, sem necessidade: o que importa é **ícone na tela inicial, tela cheia e abrir mesmo sem rede**, que um PWA entrega com o código que já existe.

O cuidado central é de **segurança e honestidade**: um service worker mal feito guarda dados de pessoas no aparelho, ou mostra uma tela velha como se fosse atual. E o app é de **agenda**: fingir que funciona offline, deixando a pessoa achar que salvou algo que não foi enviado, seria pior do que não abrir.

## Decisão
1. **Instalável:** `manifest.webmanifest` (nome, `standalone`, cores da marca, atalhos "Agendar", "Agenda do dia" e "Fila") e ícones PNG de 192 e 512 px, mais um **mascarável** (o desenho dentro da zona segura, para o sistema recortá-lo em círculo sem cortar o "&") e o do iOS. O ícone é o "&" dourado da marca sobre o azul, gerado por `scripts/gerar-icones.mjs` a partir de `public/icon.svg`. Cores da barra do sistema para tema claro e escuro.
2. **Um service worker mínimo (`public/sw.js`) que guarda só o aplicativo, nunca dados:**
   - **Só GET da própria origem.** Chamadas à API (outra origem em produção, ou `/api/`, `/actuator/` no mesmo domínio) **nunca passam pelo worker** e vão direto à rede, sempre atuais. POST e qualquer outra origem também não.
   - **Abrir uma página:** rede primeiro; sem rede, o `index.html` guardado (o app abre) ou, se nunca foi guardado, uma página "Sem conexão" com status 503.
   - **`/assets/*` (nome com hash, imutável):** cache primeiro.
   - **Ícones e manifesto:** rede primeiro, cache como reserva.
   - Nova versão: `skipWaiting` + `clients.claim` e os caches de versões antigas são apagados na ativação (basta trocar `VERSAO`).
   - Só é registrado no **site publicado** (`import.meta.env.PROD`): em desenvolvimento guardaria arquivos que mudam a cada salvamento.
3. **Aviso de falta de internet (`OfflineBanner`):** "Sem internet. O que você vê pode estar desatualizado e nenhuma ação será enviada até a conexão voltar." Aparece e some seguindo os eventos do navegador, sem roubar o foco. O app abre offline, mas as ações dependem da API: o aviso diz isso **antes** de a pessoa tentar.
4. **Botão "Instalar o app"** no menu, só quando o navegador oferece (`beforeinstallprompt`): o app assume o convite no lugar da faixa do navegador; usa o evento uma vez e some ao instalar. No iOS (que instala por "Adicionar à Tela de Início", sem evento) o botão não aparece.
5. **Cabeçalhos na Vercel:** `/sw.js` sem cache (`no-cache, no-store`, para a atualização do worker chegar) e `Service-Worker-Allowed: /`; manifesto com o tipo certo.

## O que foi descartado
- **Ações offline com fila e sincronização depois** (criar/cancelar agendamento sem rede e enviar quando voltar): é o que o item parece prometer, mas numa agenda isso cria conflitos que ninguém vê (dois tutores no mesmo horário, remarcação sobre horário já ocupado). O servidor é a fonte da verdade (ADR 0002); a resposta honesta é **avisar** que está sem internet. Fica registrado como possível evolução só para ações que não disputam horário.
- **Guardar respostas da API para "ver offline"**: dados de pessoas e de pets ficariam no aparelho (compartilhado em muitos balcões) e velhos sem aviso. Fora de questão pela LGPD (ADR 0027).
- **`vite-plugin-pwa`/Workbox:** uma dependência a mais para um worker de 100 linhas que cabe inteiro numa leitura e num teste. Revisitar se o número de estratégias crescer.
- **App nativo (Android/iOS):** custo e lojas, sem ganho que o PWA não dê aqui.

## Como foi verificado
- **Testes unitários (14):** o `sw.js` REAL é executado num ambiente simulado e prova cada regra (a API nunca é tocada nem guardada; navegação com e sem rede e sem cópia; `/assets` cache primeiro; limpeza de caches antigos); o manifesto é conferido contra os arquivos (ícones existem e são PNG de verdade); aviso offline e botão de instalar.
- **No navegador real, no build de produção (`e2e/pwa.spec.ts`, com `QC_PWA_URL`):** o worker assume a página; **o app abre com a rede desligada** e mostra o aviso; entrar offline mostra "Sem conexão" em vez de quebrar; o aviso some quando a rede volta; **nenhuma URL de API está nos caches**; e o Chrome não aponta nenhum impedimento de instalação (descontado o "modo anônimo" do próprio Playwright).

## Consequências
- O estúdio instala o app no celular em dois toques e o abre de tela cheia, mesmo com a internet instável.
- Com a internet fora, o app abre e avisa, em vez de mostrar a tela de erro do navegador.
- O primeiro acesso depois de uma versão nova traz o código novo ao recarregar (o worker assume na hora e a página já aberta continua com o código antigo até recarregar).
- Limite honesto: sem rede, nada de dados novos nem ações; só a casca do app.
