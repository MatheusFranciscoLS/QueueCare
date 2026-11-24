# ADR 0038: política de segurança de conteúdo (CSP) e cabeçalhos do site

- **Status:** Accepted (implementado em 24/11/2026)
- **Data:** 24/11/2026
- **Esforço:** BAIXO (um cabeçalho, um script movido de lugar, uma guarda no build)

## Contexto: a análise crítica
O site já enviava `X-Content-Type-Options`, `X-Frame-Options`, `Referrer-Policy` e HSTS, mas **não tinha `Content-Security-Policy`**, a defesa que limita o estrago de um XSS: com ela, mesmo que alguém consiga injetar um script, o navegador recusa executá-lo e recusa enviar dados para um endereço que não seja o da API. A autenticação guarda o token **em memória** (ADR 0003), o que já reduz o risco, mas o token em memória é exatamente o que um XSS leria. A CSP é a camada que falta, e um avaliador de segurança procura por ela.

## Decisão
1. **`Content-Security-Policy` restritiva** no `vercel.json`: `default-src 'self'`; `script-src 'self'` (**sem** `'unsafe-inline'` e sem `'unsafe-eval'`); `style-src 'self'` com `style-src-attr 'unsafe-inline'` (a interface usa atributos `style` em gráficos e medidas, e nenhuma folha de estilo injetada); `img-src 'self' data:`; `font-src 'self'` (as fontes são do próprio site); `connect-src 'self'` **mais a origem exata da API**; `manifest-src` e `worker-src` `'self'`; `object-src 'none'`; `base-uri 'self'`; `form-action 'self'`; `frame-ancestors 'none'`.
2. **O script de tema saiu do `index.html`** para `/tema.js`. Era o único script em linha, e continua sendo carregado de forma síncrona no `<head>`, então o tema salvo ainda é aplicado antes da primeira pintura (sem piscar). O arquivo entrou no cache do service worker (versão do cache subiu para `pp-v2`) para o modo sem internet.
3. **`Permissions-Policy`** fecha câmera, microfone, localização e pagamento, que o app não usa.
4. **Guarda no build** (`npm run check:build`, na CI): reprova se faltar a política, se `script-src` tiver `'unsafe-inline'`/`'unsafe-eval'`, se o `index.html` voltar a ter `<script>` ou atributo de evento em linha, e, quando o build conhece `VITE_API_URL`, se a política não incluir a origem da API.

## Por que a guarda é a parte que importa
A CSP só vale **na Vercel**: o servidor de desenvolvimento e os testes não a aplicam. Um script em linha novo, ou uma API em outro endereço, funcionaria em tudo menos em produção, onde **todas as chamadas seriam bloqueadas em silêncio**. A guarda traz esse erro para a CI. O acoplamento ao endereço da API é deliberado (a origem exata é mais segura que um curinga como `*.onrender.com`) e é a razão da terceira conferência.

## O que foi descartado
- **Nonce ou hash para o script em linha:** funcionaria, mas exige gerar nonce por requisição (o site é estático) ou recalcular o hash a cada mudança do script. Mover para um arquivo é mais simples e deixa `script-src 'self'` limpo.
- **`connect-src https://*.onrender.com`:** dispensa o acoplamento, mas deixaria o site falar com qualquer serviço hospedado ali, inclusive o de um atacante.
- **`style-src 'unsafe-inline'`:** mais frouxo que o necessário; só os atributos `style` precisam de permissão (`style-src-attr`).
- **Modo "só relatar" (`Report-Only`) antes de aplicar:** não há coletor de relatórios no custo zero; a validação foi feita carregando a pré-visualização da Vercel no navegador e lendo o console.

## Riscos conhecidos
- **Mudar o endereço da API** exige mudar a política junto (a guarda do build avisa na CI quando `VITE_API_URL` é conhecido).
- **Navegadores antigos** que não entendem `style-src-attr` aplicariam `style-src 'self'` aos atributos e perderiam alguns estilos em linha; aceitável.
- **A CSP não substitui** escapar dados e validar na API (que já são feitos), é a segunda camada.
