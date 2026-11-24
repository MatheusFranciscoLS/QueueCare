import { act, render, screen } from '@testing-library/react'
import { existsSync, readFileSync, statSync } from 'node:fs'
import { join } from 'node:path'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { InstallButton } from '../pwa/install'
import { OfflineBanner } from '../pwa/OfflineBanner'

const PUBLIC = join(__dirname, '..', '..', 'public')
const read = (name: string) => readFileSync(join(PUBLIC, name), 'utf8')

describe('manifesto', () => {
  const manifest = JSON.parse(read('manifest.webmanifest'))

  it('tem o que o navegador exige para oferecer a instalação', () => {
    expect(manifest.name).toBeTruthy()
    expect(manifest.short_name.length).toBeLessThanOrEqual(12)
    expect(manifest.start_url).toBe('/')
    expect(manifest.display).toBe('standalone')
    expect(manifest.lang).toBe('pt-BR')
    expect(manifest.theme_color).toBe('#115e7a')
  })

  it('declara ícones de 192 e 512 px e um mascarável, e todos os arquivos existem e não são vazios', () => {
    const sizes = manifest.icons.map((i: { sizes: string }) => i.sizes)
    expect(sizes).toContain('192x192')
    expect(sizes).toContain('512x512')
    expect(manifest.icons.some((i: { purpose: string }) => i.purpose === 'maskable')).toBe(true)
    for (const icon of manifest.icons as { src: string }[]) {
      const file = join(PUBLIC, icon.src.replace(/^\//, ''))
      expect(existsSync(file), icon.src).toBe(true)
      expect(statSync(file).size, icon.src).toBeGreaterThan(1000)
      // é um PNG de verdade (assinatura do formato), não um arquivo qualquer com a extensão
      expect(readFileSync(file).subarray(0, 8).toString('hex'), icon.src).toBe('89504e470d0a1a0a')
    }
  })

  it('os atalhos apontam para rotas que existem no app', () => {
    const rotas = ['/agendar', '/agenda', '/fila']
    expect(manifest.shortcuts.map((s: { url: string }) => s.url)).toEqual(rotas)
  })

  it('o index.html liga o manifesto, a cor da barra e o ícone do iOS', () => {
    const html = readFileSync(join(__dirname, '..', '..', 'index.html'), 'utf8')
    expect(html).toContain('rel="manifest" href="/manifest.webmanifest"')
    expect(html).toContain('name="theme-color"')
    expect(html).toContain('rel="apple-touch-icon"')
    expect(existsSync(join(PUBLIC, 'apple-touch-icon.png'))).toBe(true)
  })
})

/** Executa o sw.js REAL num ambiente simulado (self, caches, fetch) e devolve os ouvintes registrados. */
function loadServiceWorker(network: (url: string) => Promise<Response>) {
  const store = new Map<string, Response>()
  const names = new Set<string>(['pp-v0-app', 'outro-cache'])
  const listeners: Record<string, (event: unknown) => void> = {}
  const key = (request: string | { url: string }) => (typeof request === 'string' ? request : request.url)
  const normalize = (k: string) => (k.startsWith('/') ? `https://app.test${k}` : k)
  const fetchMock = vi.fn((request: { url: string }) => network(request.url))
  const fakeSelf = {
    location: { origin: 'https://app.test' },
    addEventListener: (type: string, fn: (event: unknown) => void) => {
      listeners[type] = fn
    },
    skipWaiting: vi.fn(() => Promise.resolve()),
    clients: { claim: vi.fn(() => Promise.resolve()) },
  }
  const fakeCaches = {
    open: async (name: string) => {
      names.add(name)
      return {
        addAll: async (urls: string[]) => {
          for (const u of urls) store.set(normalize(u), new Response('guardado:' + u))
        },
        put: async (request: string | { url: string }, response: Response) => {
          store.set(normalize(key(request)), response)
        },
      }
    },
    match: async (request: string | { url: string }) => store.get(normalize(key(request)))?.clone(),
    keys: async () => [...names],
    delete: async (name: string) => names.delete(name),
  }
  new Function('self', 'caches', 'fetch', read('sw.js'))(fakeSelf, fakeCaches, fetchMock)
  return { listeners, store, names, fetchMock, fakeSelf }
}

/** Dispara um "fetch" e devolve o que o SW respondeu (ou `undefined` se ele não quis responder). */
async function dispatchFetch(sw: ReturnType<typeof loadServiceWorker>, request: { url: string; method?: string; mode?: string }): Promise<Response | undefined> {
  let answer: Promise<Response> | undefined
  sw.listeners.fetch({ request: { method: 'GET', mode: 'no-cors', ...request }, respondWith: (p: Promise<Response>) => (answer = p) })
  return answer
}

describe('service worker (ADR 0030)', () => {
  it('ao instalar, guarda a casca do app; ao ativar, apaga os caches antigos e assume as abas', async () => {
    const sw = loadServiceWorker(async () => new Response('x'))
    const waits: Promise<unknown>[] = []
    sw.listeners.install({ waitUntil: (p: Promise<unknown>) => waits.push(p) })
    await Promise.all(waits)
    expect(sw.store.has('https://app.test/')).toBe(true)
    expect(sw.store.has('https://app.test/manifest.webmanifest')).toBe(true)
    expect(sw.store.has('https://app.test/tema.js')).toBe(true) // o script do tema (ADR 0038) também abre sem internet
    expect(sw.fakeSelf.skipWaiting).toHaveBeenCalled()

    const activation: Promise<unknown>[] = []
    sw.listeners.activate({ waitUntil: (p: Promise<unknown>) => activation.push(p) })
    await Promise.all(activation)
    expect([...sw.names]).toEqual(['pp-v2-app']) // o de versão antiga e o alheio saíram
    expect(sw.fakeSelf.clients.claim).toHaveBeenCalled()
  })

  it('NUNCA toca na API: POST, outra origem, /api/ e /actuator/ vão direto à rede', async () => {
    const sw = loadServiceWorker(async () => new Response('x'))
    expect(await dispatchFetch(sw, { url: 'https://app.test/agenda', method: 'POST' })).toBeUndefined()
    expect(await dispatchFetch(sw, { url: 'https://api.render.test/api/me' })).toBeUndefined() // outra origem (a API em produção)
    expect(await dispatchFetch(sw, { url: 'https://app.test/api/me' })).toBeUndefined()
    expect(await dispatchFetch(sw, { url: 'https://app.test/actuator/health' })).toBeUndefined()
    expect(sw.fetchMock).not.toHaveBeenCalled()
    expect(sw.store.size).toBe(0) // e nada de API foi guardado
  })

  it('navegação: usa a rede e atualiza a cópia; sem rede, abre a cópia guardada', async () => {
    let online = true
    const sw = loadServiceWorker(async () => {
      if (!online) throw new TypeError('offline')
      return new Response('<html>novo</html>')
    })
    const first = await dispatchFetch(sw, { url: 'https://app.test/agenda', mode: 'navigate' })
    expect(await first!.text()).toBe('<html>novo</html>')
    await Promise.resolve() // deixa o cache.put terminar
    expect(await sw.store.get('https://app.test/')!.clone().text()).toBe('<html>novo</html>')

    online = false
    const offline = await dispatchFetch(sw, { url: 'https://app.test/fila', mode: 'navigate' })
    expect(await offline!.text()).toBe('<html>novo</html>')
  })

  it('navegação sem rede e sem cópia mostra uma página de "Sem conexão" (503), nunca a tela de erro do navegador', async () => {
    const sw = loadServiceWorker(async () => {
      throw new TypeError('offline')
    })
    const answer = await dispatchFetch(sw, { url: 'https://app.test/', mode: 'navigate' })
    expect(answer!.status).toBe(503)
    expect(await answer!.text()).toContain('Sem conexão')
  })

  it('/assets/ (arquivos com hash) são cache primeiro: a segunda vez nem chama a rede', async () => {
    const sw = loadServiceWorker(async () => new Response('console.log(1)'))
    const url = 'https://app.test/assets/index-ab12cd.js'
    expect(await (await dispatchFetch(sw, { url }))!.text()).toBe('console.log(1)')
    expect(sw.fetchMock).toHaveBeenCalledTimes(1)
    await Promise.resolve()
    expect(await (await dispatchFetch(sw, { url }))!.text()).toBe('console.log(1)')
    expect(sw.fetchMock).toHaveBeenCalledTimes(1) // veio do cache
  })

  it('ícones e manifesto: rede primeiro, e o cache serve quando a rede cai', async () => {
    let online = true
    const sw = loadServiceWorker(async () => {
      if (!online) throw new TypeError('offline')
      return new Response('icone')
    })
    const url = 'https://app.test/icon-192.png'
    expect(await (await dispatchFetch(sw, { url }))!.text()).toBe('icone')
    await Promise.resolve()
    online = false
    expect(await (await dispatchFetch(sw, { url }))!.text()).toBe('icone')
  })
})

describe('aviso de falta de internet', () => {
  afterEach(() => vi.restoreAllMocks())

  it('aparece quando a internet cai e some quando volta, sem tirar o foco de ninguém', () => {
    vi.spyOn(navigator, 'onLine', 'get').mockReturnValue(true)
    render(<OfflineBanner />)
    expect(screen.queryByRole('status')).not.toBeInTheDocument()

    act(() => window.dispatchEvent(new Event('offline')))
    expect(screen.getByRole('status')).toHaveTextContent(/Sem internet/)
    expect(screen.getByRole('status')).toHaveTextContent(/nenhuma ação será enviada/)

    act(() => window.dispatchEvent(new Event('online')))
    expect(screen.queryByRole('status')).not.toBeInTheDocument()
  })

  it('já abre avisando se a página carregou sem internet', () => {
    vi.spyOn(navigator, 'onLine', 'get').mockReturnValue(false)
    render(<OfflineBanner />)
    expect(screen.getByRole('status')).toHaveTextContent(/Sem internet/)
  })
})

describe('instalar o app', () => {
  it('o botão só existe quando o navegador oferece a instalação; usa o convite uma vez e some', async () => {
    render(<InstallButton />)
    expect(screen.queryByRole('button', { name: 'Instalar o app' })).not.toBeInTheDocument()

    const prompt = vi.fn().mockResolvedValue(undefined)
    const event = Object.assign(new Event('beforeinstallprompt', { cancelable: true }), { prompt, userChoice: Promise.resolve({ outcome: 'accepted' as const }) })
    act(() => {
      window.dispatchEvent(event)
    })
    expect(event.defaultPrevented).toBe(true) // o app assume o convite no lugar da faixa do navegador
    const button = await screen.findByRole('button', { name: 'Instalar o app' })

    await act(async () => button.click())
    expect(prompt).toHaveBeenCalledTimes(1)
    expect(screen.queryByRole('button', { name: 'Instalar o app' })).not.toBeInTheDocument()
  })

  it('depois de instalado (appinstalled) o botão some', async () => {
    render(<InstallButton />)
    const event = Object.assign(new Event('beforeinstallprompt', { cancelable: true }), { prompt: vi.fn(), userChoice: Promise.resolve({ outcome: 'dismissed' as const }) })
    act(() => {
      window.dispatchEvent(event)
    })
    await screen.findByRole('button', { name: 'Instalar o app' })
    act(() => window.dispatchEvent(new Event('appinstalled')))
    expect(screen.queryByRole('button', { name: 'Instalar o app' })).not.toBeInTheDocument()
  })
})
