import { act, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useNavigate } from 'react-router-dom'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { App } from '../App'
import { ApiError, apiRequest } from '../api/client'
import { describeError } from '../api/messages'
import { safeDestination } from '../auth/destinations'
import { AuthProvider, useAuth, type AuthApi } from '../auth/session'
import { json, mockApi, NEW_PASSWORD, PASSWORD, renderApp, token, USER } from './helpers'

afterEach(() => vi.unstubAllGlobals())

/** Promessa que o teste resolve à mão, para provar o que acontece quando uma resposta chega ATRASADA. */
function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((r) => (resolve = r))
  return { promise, resolve }
}

async function login(user: ReturnType<typeof userEvent.setup>) {
  await user.type(screen.getByLabelText('E-mail'), 'maria@exemplo.test')
  await user.type(screen.getByLabelText('Senha'), PASSWORD)
  await user.click(screen.getByRole('button', { name: 'Entrar' }))
}

const pending = { ...USER, role: 'PROVIDER', name: 'Oficina Central', mustChangePassword: true }

describe('sair durante uma operação pendente (M1)', () => {
  it('a consulta a /api/me que chega DEPOIS do "Sair" não reabre a sessão', async () => {
    const slowMe = deferred<Response>()
    mockApi({
      'POST /api/auth/login': () => token('t-velho', 900, true),
      'GET /api/me': (c) => (c.authorization === 'Bearer t-novo' ? slowMe.promise : json(200, pending)),
      'POST /api/auth/change-password': () => token('t-novo', 900, false),
    })
    const user = userEvent.setup()
    renderApp('/entrar')
    await login(user)
    await screen.findByRole('heading', { name: 'Defina a sua senha' })
    await user.type(screen.getByLabelText('Senha atual'), PASSWORD)
    await user.type(screen.getByLabelText('Nova senha'), NEW_PASSWORD)
    await user.type(screen.getByLabelText('Repita a nova senha'), NEW_PASSWORD)
    await user.click(screen.getByRole('button', { name: 'Salvar nova senha' }))
    await waitFor(() => expect(vi.mocked(fetch).mock.calls.some(([p]) => p === '/api/auth/change-password')).toBe(true))

    await user.click(screen.getByRole('button', { name: 'Sair' })) // sai com /api/me ainda pendente
    expect(await screen.findByRole('heading', { name: 'Entrar' })).toBeInTheDocument()
    await act(async () => slowMe.resolve(json(200, { ...pending, mustChangePassword: false })))

    expect(screen.getByRole('heading', { name: 'Entrar' })).toBeInTheDocument()
    expect(screen.queryByText(/Olá/)).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Sair' })).not.toBeInTheDocument()
  })
})

/** Componente de apoio: expõe a API de sessão e a navegação para os testes de borda. */
function Harness({ onApi }: { onApi: (api: AuthApi, navigate: ReturnType<typeof useNavigate>) => void }) {
  const api = useAuth()
  const navigate = useNavigate()
  onApi(api, navigate)
  return null
}

function renderWithHarness(path = '/entrar') {
  let api!: AuthApi
  let navigate!: ReturnType<typeof useNavigate>
  render(
    <MemoryRouter initialEntries={[path]}>
      <AuthProvider>
        <Harness onApi={(a, n) => ((api = a), (navigate = n))} />
        <App />
      </AuthProvider>
    </MemoryRouter>,
  )
  return { api: () => api, navigate: () => navigate }
}

describe('respostas atrasadas e fora de ordem', () => {
  it('um 401 de uma chamada da sessão ANTIGA não derruba a sessão NOVA', async () => {
    const slow = deferred<Response>()
    let nextToken = 't-1'
    mockApi({
      'POST /api/auth/login': () => token(nextToken),
      'GET /api/me': () => json(200, USER),
      'GET /api/lenta': () => slow.promise,
    })
    const view = renderWithHarness()
    await act(async () => view.api().login('maria@exemplo.test', PASSWORD))
    const old = view.api().request('/api/lenta').catch((e) => e) // sessão 1 (token t-1) com uma chamada pendente
    act(() => view.api().logout())
    nextToken = 't-2'
    await act(async () => view.api().login('maria@exemplo.test', PASSWORD))
    expect(view.api().user?.email).toBe('maria@exemplo.test')

    await act(async () => slow.resolve(new Response(null, { status: 401 })))
    expect(((await old) as ApiError).status).toBe(401)

    expect(view.api().user).not.toBeNull() // a sessão nova continua de pé
    expect(await screen.findByRole('heading', { name: 'Olá, Maria Souza' })).toBeInTheDocument()
  })

  it('um login que termina depois de OUTRO login valer não sobrepõe o mais novo', async () => {
    const first = deferred<Response>()
    let calls = 0
    mockApi({
      'POST /api/auth/login': () => (++calls === 1 ? first.promise : token('t-2')),
      'GET /api/me': (c) => json(200, c.authorization === 'Bearer t-2' ? { ...USER, name: 'Segunda' } : { ...USER, name: 'Primeira' }),
    })
    const view = renderWithHarness()
    const slowLogin = view.api().login('a@exemplo.test', PASSWORD)
    await act(async () => view.api().login('b@exemplo.test', PASSWORD))
    await act(async () => first.resolve(token('t-1')))
    await slowLogin

    expect(view.api().user?.name).toBe('Segunda')
  })
})

describe('guarda de troca de senha pendente', () => {
  it('nenhuma navegação (início, cadastro, entrar, voltar) tira a pessoa da troca', async () => {
    mockApi({ 'POST /api/auth/login': () => token('t-1', 900, true), 'GET /api/me': () => json(200, pending) })
    const view = renderWithHarness()
    await act(async () => view.api().login('maria@exemplo.test', PASSWORD))
    await screen.findByRole('heading', { name: 'Defina a sua senha' })

    for (const destination of ['/', '/cadastro', '/entrar']) {
      act(() => view.navigate()(destination))
      expect(await screen.findByRole('heading', { name: 'Defina a sua senha' })).toBeInTheDocument()
      expect(screen.queryByText(/Olá/)).not.toBeInTheDocument()
    }
    act(() => view.navigate()(-1))
    expect(await screen.findByRole('heading', { name: 'Defina a sua senha' })).toBeInTheDocument()
    act(() => view.navigate()(1))
    expect(await screen.findByRole('heading', { name: 'Defina a sua senha' })).toBeInTheDocument()
  })

  it('rota desconhecida com a troca pendente mostra só "não encontrada", sem dados da conta', async () => {
    mockApi({ 'POST /api/auth/login': () => token('t-1', 900, true), 'GET /api/me': () => json(200, pending) })
    const view = renderWithHarness()
    await act(async () => view.api().login('maria@exemplo.test', PASSWORD))
    await screen.findByRole('heading', { name: 'Defina a sua senha' })

    act(() => view.navigate()('/qualquer-coisa'))

    expect(await screen.findByRole('heading', { name: 'Página não encontrada' })).toBeInTheDocument()
    expect(screen.queryByText('maria@exemplo.test')).not.toBeInTheDocument()
  })

  it('sem login, abrir direto uma rota protegida leva à entrada', async () => {
    mockApi({})
    renderApp('/trocar-senha')
    expect(await screen.findByRole('heading', { name: 'Entrar' })).toBeInTheDocument()
  })
})

describe('destino depois do login (L1)', () => {
  it.each([
    ['/', '/'],
    ['/trocar-senha', '/trocar-senha'],
    ['https://evil.test', '/'],
    ['//evil.test', '/'],
    ['/entrar', '/'],
    ['/qualquer', '/'],
    ['javascript:alert(1)', '/'],
    [undefined, '/'],
    [123, '/'],
    [{ a: 1 }, '/'],
  ])('%j vira %s', (candidate, expected) => {
    expect(safeDestination(candidate)).toBe(expected)
  })
})

describe('erros', () => {
  it('AbortError é relançado como está, não vira ApiError de rede (L3)', async () => {
    const abort = new DOMException('cancelado', 'AbortError')
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(abort))
    const controller = new AbortController()

    await expect(apiRequest('/api/x', { signal: controller.signal })).rejects.toBe(abort)
  })

  it('409 sem código conhecido usa mensagem genérica e NUNCA o detalhe bruto da API (L2)', () => {
    const message = describeError(new ApiError(409, 'detalhe técnico: tabela users, constraint x'))
    expect(message).not.toContain('detalhe técnico')
    expect(message).toContain('conflita')
  })
})

describe('formulários', () => {
  it('um segundo envio durante o primeiro é ignorado (duplo clique/Enter repetido)', async () => {
    const slow = deferred<Response>()
    const { calls } = mockApi({ 'POST /api/auth/login': () => slow.promise, 'GET /api/me': () => json(200, USER) })
    const user = userEvent.setup()
    renderApp('/entrar')
    await user.type(screen.getByLabelText('E-mail'), 'maria@exemplo.test')
    await user.type(screen.getByLabelText('Senha'), PASSWORD)

    await user.keyboard('{Enter}')
    await user.keyboard('{Enter}')
    await user.keyboard('{Enter}')
    await act(async () => slow.resolve(token()))

    expect(calls.filter((c) => c.path === '/api/auth/login')).toHaveLength(1)
    expect(await screen.findByRole('heading', { name: 'Olá, Maria Souza' })).toBeInTheDocument()
  })

  it('recarregar a página NÃO restaura a sessão (token só em memória)', async () => {
    mockApi({ 'POST /api/auth/login': () => token(), 'GET /api/me': () => json(200, USER) })
    const user = userEvent.setup()
    const first = renderApp('/entrar')
    await login(user)
    await screen.findByRole('heading', { name: 'Olá, Maria Souza' })
    first.unmount() // "recarregar": a memória some

    renderApp('/')

    expect(await screen.findByRole('heading', { name: 'Entrar' })).toBeInTheDocument()
  })

  it('o erro de um campo some quando a pessoa começa a corrigi-lo, e o foco vai ao primeiro inválido', async () => {
    mockApi({})
    const user = userEvent.setup()
    renderApp('/cadastro')

    await user.click(screen.getByRole('button', { name: 'Criar conta' }))
    expect(await screen.findByText('Informe o seu nome.')).toBeInTheDocument()
    expect(screen.getByLabelText('Nome')).toHaveFocus()

    await user.type(screen.getByLabelText('Nome'), 'M')

    expect(screen.queryByText('Informe o seu nome.')).not.toBeInTheDocument()
    expect(screen.getByLabelText('Nome')).toHaveFocus() // digitar não rouba o foco
    expect(screen.getByText('Informe um e-mail válido.')).toBeInTheDocument() // os outros erros continuam até serem corrigidos
  })
})
