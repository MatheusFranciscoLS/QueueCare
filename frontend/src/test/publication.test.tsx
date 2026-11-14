import { act, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { apiUrl } from '../api/base'
import { apiRequest } from '../api/client'
import { SLOW_AFTER_MS, trackRequest } from '../api/slow'
import { WakingBanner } from '../pages/WakingBanner'

afterEach(() => {
  vi.unstubAllEnvs()
  vi.unstubAllGlobals()
  vi.useRealTimers()
})

describe('endereço da API', () => {
  it('sem VITE_API_URL o caminho segue relativo (o Vite redireciona em desenvolvimento)', () => {
    vi.stubEnv('VITE_API_URL', '')
    expect(apiUrl('/api/me')).toBe('/api/me')
  })

  it('com VITE_API_URL junta a origem e o caminho, com ou sem barra final', () => {
    vi.stubEnv('VITE_API_URL', 'https://queuecare-api.onrender.com')
    expect(apiUrl('/api/me')).toBe('https://queuecare-api.onrender.com/api/me')
    vi.stubEnv('VITE_API_URL', '  https://queuecare-api.onrender.com/  ')
    expect(apiUrl('/api/appointments/mine?page=0&size=50')).toBe('https://queuecare-api.onrender.com/api/appointments/mine?page=0&size=50')
  })

  it.each(['queuecare-api.onrender.com', 'ftp://api.exemplo.com', 'https://api.exemplo.com/v1', 'https://api.exemplo.com?x=1', 'https://api.exemplo.com/#a', 'https://usuario:senha@api.exemplo.com', 'https://api.exemplo.com@evil.example', 'javascript:alert(1)'])(
    'valor inválido (%s) falha alto em vez de mandar o token para um lugar errado',
    (value) => {
      vi.stubEnv('VITE_API_URL', value)
      expect(() => apiUrl('/api/me')).toThrow(/VITE_API_URL inválida/)
    },
  )

  it('o cliente HTTP usa o endereço configurado', async () => {
    vi.stubEnv('VITE_API_URL', 'https://queuecare-api.onrender.com')
    const fetchMock = vi.fn(async () => new Response(JSON.stringify({ ok: true }), { status: 200, headers: { 'Content-Type': 'application/json' } }))
    vi.stubGlobal('fetch', fetchMock)
    await apiRequest('/api/me', { token: 't-1' })
    expect(fetchMock).toHaveBeenCalledWith('https://queuecare-api.onrender.com/api/me', expect.objectContaining({ credentials: 'omit' }))
  })
})

describe('aviso de servidor acordando', () => {
  beforeEach(() => {
    vi.useFakeTimers()
  })

  it('requisição rápida nunca mostra o aviso', async () => {
    render(<WakingBanner />)
    const done = trackRequest()
    await act(async () => vi.advanceTimersByTime(SLOW_AFTER_MS - 1))
    done()
    await act(async () => vi.advanceTimersByTime(SLOW_AFTER_MS * 2))
    expect(screen.queryByRole('status')).not.toBeInTheDocument() // oculto: não existe para leitores de tela
  })

  it('requisição que passa do limite mostra o aviso e ele some quando ela termina', async () => {
    render(<WakingBanner />)
    const done = trackRequest()
    await act(async () => vi.advanceTimersByTime(SLOW_AFTER_MS))
    expect(screen.getByRole('status')).toHaveTextContent('o servidor dorme quando ninguém usa')
    await act(async () => done())
    expect(screen.queryByRole('status')).not.toBeInTheDocument()
  })

  it('com várias lentas o aviso só some quando TODAS terminam; terminar duas vezes não desbalanceia', async () => {
    render(<WakingBanner />)
    const a = trackRequest()
    const b = trackRequest()
    await act(async () => vi.advanceTimersByTime(SLOW_AFTER_MS))
    expect(screen.getByRole('status')).toBeInTheDocument()
    await act(async () => a())
    await act(async () => a()) // repetir o fim não pode contar de novo
    expect(screen.getByRole('status')).toBeInTheDocument()
    await act(async () => b())
    expect(screen.queryByRole('status')).not.toBeInTheDocument()
    // depois de tudo, uma nova requisição rápida continua sem aviso
    const c = trackRequest()
    c()
    await act(async () => vi.advanceTimersByTime(SLOW_AFTER_MS * 2))
    expect(screen.queryByRole('status')).not.toBeInTheDocument()
  })

  it('o cliente HTTP marca a requisição como lenta enquanto a resposta não chega e libera ao chegar ou ao falhar', async () => {
    render(<WakingBanner />)
    let respond!: (response: Response) => void
    vi.stubGlobal('fetch', vi.fn(() => new Promise<Response>((resolve) => (respond = resolve))))
    const pending = apiRequest('/api/me')
    await act(async () => vi.advanceTimersByTime(SLOW_AFTER_MS))
    expect(screen.getByRole('status')).toBeInTheDocument()
    await act(async () => respond(new Response('{}', { status: 200, headers: { 'Content-Type': 'application/json' } })))
    await pending
    expect(screen.queryByRole('status')).not.toBeInTheDocument()

    vi.stubGlobal('fetch', vi.fn(() => new Promise<Response>((_, reject) => setTimeout(() => reject(new TypeError('falhou')), SLOW_AFTER_MS + 100))))
    const failing = apiRequest('/api/me').catch((e) => e)
    await act(async () => vi.advanceTimersByTime(SLOW_AFTER_MS))
    expect(screen.getByRole('status')).toBeInTheDocument()
    await act(async () => vi.advanceTimersByTime(200))
    expect(((await failing) as { reason?: string }).reason).toBe('NETWORK')
    expect(screen.queryByRole('status')).not.toBeInTheDocument()
  })
})
