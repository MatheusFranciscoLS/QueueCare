import { afterEach, describe, expect, it, vi } from 'vitest'
import { warmUpServer } from '../api/warmup'

afterEach(() => {
  vi.unstubAllGlobals()
  vi.unstubAllEnvs()
})

describe('acordar a API ao abrir o site (ADR 0037)', () => {
  it('faz UMA chamada sem credencial, sem ler a resposta, no endereço da API publicada', () => {
    vi.stubEnv('VITE_API_URL', 'https://api.exemplo.com')
    const fetchMock = vi.fn(async () => new Response(null))
    vi.stubGlobal('fetch', fetchMock)
    warmUpServer()
    expect(fetchMock).toHaveBeenCalledTimes(1)
    expect(fetchMock).toHaveBeenCalledWith('https://api.exemplo.com/actuator/health/liveness', { method: 'GET', mode: 'no-cors', credentials: 'omit', cache: 'no-store' })
  })

  it('em desenvolvimento (sem VITE_API_URL) não faz nada: a API local não dorme', () => {
    vi.stubEnv('VITE_API_URL', '')
    const fetchMock = vi.fn()
    vi.stubGlobal('fetch', fetchMock)
    warmUpServer()
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('falha em silêncio: rede fora do ar ou endereço inválido não derrubam o site', async () => {
    vi.stubEnv('VITE_API_URL', 'https://api.exemplo.com')
    vi.stubGlobal('fetch', vi.fn(async () => { throw new TypeError('Failed to fetch') }))
    expect(() => warmUpServer()).not.toThrow()
    await Promise.resolve() // deixa a promessa rejeitada ser tratada: nenhuma rejeição não tratada
    vi.stubEnv('VITE_API_URL', 'isso-nao-e-um-endereco')
    expect(() => warmUpServer()).not.toThrow()
  })
});
