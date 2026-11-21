/* Service worker do Pelo & Ponto (ADR 0030).
 *
 * Faz UMA coisa: deixa o APLICATIVO (HTML, scripts, estilos, ícones) abrir mesmo sem internet. Nunca guarda dados da pessoa:
 *   - chamadas à API (outra origem em produção, ou /api/ no mesmo domínio) NÃO passam por aqui: vão direto à rede, sempre atuais;
 *   - só requisições GET da própria origem são tratadas; qualquer outra coisa segue o caminho normal do navegador.
 *
 * Estratégias:
 *   - navegação (abrir uma página): rede primeiro; sem rede, o index.html guardado (o app abre e mostra "Sem conexão" nas chamadas);
 *   - /assets/* (arquivos com hash no nome, que nunca mudam): cache primeiro;
 *   - ícones e manifesto: rede primeiro, cache como reserva.
 *
 * Mudou algo aqui? Troque VERSAO: os caches antigos são apagados na ativação.
 */
const VERSAO = 'pp-v1'
const CACHE = `${VERSAO}-app`
const INICIAIS = ['/', '/manifest.webmanifest', '/icon-192.png', '/icon-512.png']

const PAGINA_OFFLINE = `<!doctype html><html lang="pt-BR"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Sem conexão</title>
<body style="font-family:system-ui,sans-serif;margin:0;display:grid;place-items:center;min-height:100vh;background:#eef2f4;color:#14202b"><main style="max-width:28rem;padding:1.5rem">
<h1 style="font-size:1.5rem">Sem conexão</h1><p>O Pelo &amp; Ponto precisa de internet na primeira vez. Conecte-se e abra de novo.</p></main></body></html>`

self.addEventListener('install', (event) => {
  event.waitUntil(
    caches
      .open(CACHE)
      .then((cache) => cache.addAll(INICIAIS))
      .then(() => self.skipWaiting()),
  )
})

self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches
      .keys()
      .then((nomes) => Promise.all(nomes.filter((n) => n !== CACHE).map((n) => caches.delete(n))))
      .then(() => self.clients.claim()),
  )
})

/** Esta requisição é do aplicativo (e portanto pode ser guardada)? Só GET, só a própria origem, nunca a API. */
function ehDoAplicativo(request) {
  if (request.method !== 'GET') return false
  const url = new URL(request.url)
  if (url.origin !== self.location.origin) return false
  if (url.pathname.startsWith('/api/') || url.pathname.startsWith('/actuator/')) return false
  return true
}

self.addEventListener('fetch', (event) => {
  const { request } = event
  if (!ehDoAplicativo(request)) return // não responde: o navegador segue o caminho normal (rede)

  if (request.mode === 'navigate') {
    event.respondWith(
      fetch(request)
        .then((resposta) => {
          if (resposta.ok) {
            const copia = resposta.clone()
            caches.open(CACHE).then((cache) => cache.put('/', copia))
          }
          return resposta
        })
        .catch(() => caches.match('/').then((guardada) => guardada || new Response(PAGINA_OFFLINE, { status: 503, headers: { 'Content-Type': 'text/html; charset=utf-8' } }))),
    )
    return
  }

  const url = new URL(request.url)
  if (url.pathname.startsWith('/assets/')) {
    event.respondWith(
      caches.match(request).then(
        (guardada) =>
          guardada ||
          fetch(request).then((resposta) => {
            if (resposta.ok) {
              const copia = resposta.clone()
              caches.open(CACHE).then((cache) => cache.put(request, copia))
            }
            return resposta
          }),
      ),
    )
    return
  }

  event.respondWith(
    fetch(request)
      .then((resposta) => {
        if (resposta.ok) {
          const copia = resposta.clone()
          caches.open(CACHE).then((cache) => cache.put(request, copia))
        }
        return resposta
      })
      .catch(() => caches.match(request).then((guardada) => guardada || Response.error())),
  )
})
