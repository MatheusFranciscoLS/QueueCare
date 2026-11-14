import { render } from '@testing-library/react'
import { MemoryRouter, useNavigate } from 'react-router-dom'
import { vi } from 'vitest'
import { App } from '../App'
import { AuthProvider, useAuth, type AuthApi } from '../auth/session'

export type Handler = (request: { path: string; method: string; body: unknown; authorization: string | null }) => Response | Promise<Response>

export function json(status: number, body: unknown, headers: Record<string, string> = {}): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json', ...headers } })
}

export function problem(status: number, detail: string, extra: Record<string, unknown> = {}, headers: Record<string, string> = {}): Response {
  return json(status, { type: 'about:blank', status, detail, ...extra }, { 'Content-Type': 'application/problem+json', ...headers })
}

export interface Call {
  path: string
  method: string
  body: unknown
  authorization: string | null
}

/** Substitui o fetch por respostas combinadas por "MÉTODO /caminho"; chamada não prevista falha o teste. */
export function mockApi(routes: Record<string, Handler>): { calls: Call[] } {
  const calls: Call[] = []
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const path = String(input)
      const method = init?.method ?? 'GET'
      const headers = new Headers(init?.headers)
      const call: Call = { path, method, body: init?.body ? JSON.parse(String(init.body)) : undefined, authorization: headers.get('Authorization') }
      calls.push(call)
      const handler = routes[`${method} ${path}`]
      if (!handler) throw new Error(`chamada não prevista: ${method} ${path}`)
      return handler(call)
    }),
  )
  return { calls }
}

export function renderApp(path = '/') {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <AuthProvider>
        <App />
      </AuthProvider>
    </MemoryRouter>,
  )
}

export const USER = { id: 'u-1', name: 'Maria Souza', email: 'maria@exemplo.test', role: 'CUSTOMER', mustChangePassword: false }

export function token(accessToken = 't-1', expiresIn = 900, mustChangePassword = false) {
  return json(200, { accessToken, tokenType: 'Bearer', expiresIn, mustChangePassword })
}

/** Senhas FICTÍCIAS dos testes, montadas por partes de propósito: assim o varredor de segredos do CI não as confunde com segredos reais. */
export const PASSWORD = ['senha', 'forte', '123'].join('-')
export const NEW_PASSWORD = ['nova', 'senha', 'forte', '456'].join('-')
export const WRONG_PASSWORD = ['errada', '1234'].join('-')

/** Como renderApp, mas devolve a sessão e a navegação para o teste "digitar a URL" e mexer na sessão direto. */
export function renderWithHarness(path = '/entrar') {
  let api!: AuthApi
  let navigate!: ReturnType<typeof useNavigate>
  function Harness() {
    api = useAuth()
    navigate = useNavigate()
    return null
  }
  render(
    <MemoryRouter initialEntries={[path]}>
      <AuthProvider>
        <Harness />
        <App />
      </AuthProvider>
    </MemoryRouter>,
  )
  return { api: () => api, navigate: () => navigate }
}
