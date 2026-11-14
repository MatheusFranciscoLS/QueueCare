import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiError, apiRequest } from '../api/client'
import { describeError } from '../api/messages'
import { validateNewPassword } from '../auth/passwordPolicy'
import { json, mockApi, problem } from './helpers'

afterEach(() => vi.unstubAllGlobals())

/** Espera a promessa falhar e devolve o erro já tipado. */
const failure = (promise: Promise<unknown>): Promise<ApiError> =>
  promise.then(
    () => {
      throw new Error('esperava um erro')
    },
    (e) => e as ApiError,
  )

describe('apiRequest', () => {
  it('anexa o token Bearer, envia JSON e não usa cookies', async () => {
    const { calls } = mockApi({ 'POST /api/x': () => json(200, { ok: true }) })
    const spy = vi.mocked(fetch)

    await expect(apiRequest('/api/x', { method: 'POST', body: { a: 1 }, token: 'abc' })).resolves.toEqual({ ok: true })

    expect(calls[0].authorization).toBe('Bearer abc')
    expect(calls[0].body).toEqual({ a: 1 })
    expect(spy.mock.calls[0][1]?.credentials).toBe('omit')
  })

  it('não envia Authorization sem token', async () => {
    const { calls } = mockApi({ 'GET /api/x': () => json(200, {}) })
    await apiRequest('/api/x')
    expect(calls[0].authorization).toBeNull()
  })

  it('204 devolve undefined', async () => {
    mockApi({ 'POST /api/x': () => new Response(null, { status: 204 }) })
    await expect(apiRequest('/api/x', { method: 'POST' })).resolves.toBeUndefined()
  })

  it('converte problem+json em ApiError com status, código e erros por campo', async () => {
    mockApi({ 'POST /api/x': () => problem(400, 'dados inválidos', { reason: 'ALGO', errors: { password: 'curta', n: 3 } }) })
    const error = await failure(apiRequest('/api/x', { method: 'POST' }))

    expect(error).toBeInstanceOf(ApiError)
    expect(error.status).toBe(400)
    expect(error.reason).toBe('ALGO')
    expect(error.fieldErrors).toEqual({ password: 'curta' }) // só textos
  })

  it('erro sem corpo JSON vira ApiError só com o status; Retry-After é lido', async () => {
    mockApi({ 'GET /a': () => new Response('<html>', { status: 502 }), 'GET /b': () => problem(429, 'x', {}, { 'Retry-After': '120' }) })
    expect((await failure(apiRequest('/a'))).status).toBe(502)
    expect((await failure(apiRequest('/b'))).retryAfterSeconds).toBe(120)
  })

  it('falha de rede vira ApiError de status 0', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('Failed to fetch')))
    const error = await failure(apiRequest('/api/x'))
    expect(error).toBeInstanceOf(ApiError)
    expect(error.status).toBe(0)
  })
})

describe('describeError', () => {
  it('traduz códigos estáveis e status, sem repetir dados', () => {
    expect(describeError(new ApiError(400, '', 'INVALID_CURRENT_PASSWORD'))).toBe('A senha atual não confere.')
    expect(describeError(new ApiError(401, ''))).toBe('E-mail ou senha incorretos.')
    expect(describeError(new ApiError(429, '', undefined, {}, 120))).toContain('2 minutos')
    expect(describeError(new ApiError(429, '', undefined, {}, 30))).toContain('30 segundos')
    expect(describeError(new ApiError(0, ''))).toContain('Sem conexão')
    expect(describeError(new ApiError(500, 'stack secreta'))).not.toContain('stack')
    expect(describeError(new Error('qualquer'))).toBe('Algo deu errado. Tente novamente.')
  })
})

describe('validateNewPassword', () => {
  it('segue a política da API: 10 caracteres, 72 bytes, diferente da atual', () => {
    expect(validateNewPassword('curta')).toMatch(/pelo menos 10/)
    expect(validateNewPassword('dez-chars-1')).toBeNull()
    expect(validateNewPassword('é'.repeat(40))).toMatch(/72 bytes/) // 40 caracteres, 80 bytes
    expect(validateNewPassword('é'.repeat(36))).toBeNull() // 72 bytes exatos
    expect(validateNewPassword('mesma-senha-1', 'mesma-senha-1')).toMatch(/diferente da atual/)
    expect(validateNewPassword('😀'.repeat(10))).toBeNull() // 10 caracteres, 40 bytes
    expect(validateNewPassword('😀'.repeat(19))).toMatch(/72 bytes/) // 76 bytes
  })
})
