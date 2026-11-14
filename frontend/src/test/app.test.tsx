import { act, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { json, mockApi, NEW_PASSWORD, PASSWORD, problem, renderApp, token, USER, WRONG_PASSWORD } from './helpers'

beforeEach(() => vi.useRealTimers())
afterEach(() => vi.unstubAllGlobals())

async function fillLogin(user: ReturnType<typeof userEvent.setup>, email = 'maria@exemplo.test', password: string = PASSWORD) {
  await user.type(screen.getByLabelText('E-mail'), email)
  await user.type(screen.getByLabelText('Senha'), password)
  await user.click(screen.getByRole('button', { name: 'Entrar' }))
}

describe('entrada', () => {
  it('quem não está logado vai para a tela de entrada', async () => {
    mockApi({})
    renderApp('/')
    expect(await screen.findByRole('heading', { name: 'Entrar' })).toBeInTheDocument()
  })

  it('login com sucesso mostra o início do perfil e guarda o token só em memória', async () => {
    const { calls } = mockApi({
      'POST /api/auth/login': () => token('t-1'),
      'GET /api/me': () => json(200, USER),
    })
    const user = userEvent.setup()
    renderApp('/entrar')

    await fillLogin(user)

    expect(await screen.findByRole('heading', { name: 'Olá, Maria Souza' })).toBeInTheDocument()
    expect(screen.getByText('Cliente')).toBeInTheDocument()
    expect(calls[0].body).toEqual({ email: 'maria@exemplo.test', password: PASSWORD })
    expect(calls[1].authorization).toBe('Bearer t-1')
    // ADR 0008: nenhum armazenamento do navegador guarda o token
    expect(window.localStorage.length).toBe(0)
    expect(window.sessionStorage.length).toBe(0)
    expect(document.cookie).toBe('')
  })

  it('credenciais erradas mostram a mensagem sem revelar o motivo e mantêm a tela', async () => {
    mockApi({ 'POST /api/auth/login': () => problem(401, 'credenciais inválidas') })
    const user = userEvent.setup()
    renderApp('/entrar')

    await fillLogin(user, 'maria@exemplo.test', WRONG_PASSWORD)

    expect(await screen.findByRole('alert')).toHaveTextContent('E-mail ou senha incorretos.')
    expect(screen.getByRole('heading', { name: 'Entrar' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Entrar' })).toBeEnabled() // pode tentar de novo
  })

  it('bloqueio temporário (429) informa o tempo de espera', async () => {
    mockApi({ 'POST /api/auth/login': () => problem(429, 'muitas tentativas', {}, { 'Retry-After': '600' }) })
    const user = userEvent.setup()
    renderApp('/entrar')

    await fillLogin(user)

    expect(await screen.findByRole('alert')).toHaveTextContent('10 minutos')
  })

  it('o botão só habilita com e-mail e senha preenchidos', async () => {
    mockApi({})
    renderApp('/entrar')
    expect(screen.getByRole('button', { name: 'Entrar' })).toBeDisabled()
  })

})

describe('troca de senha obrigatória', () => {
  const pending = { ...USER, role: 'PROVIDER', name: 'Oficina Central', mustChangePassword: true }

  it('conta com senha provisória é levada à troca e não vê o resto', async () => {
    mockApi({ 'POST /api/auth/login': () => token('t-1', 900, true), 'GET /api/me': () => json(200, pending) })
    const user = userEvent.setup()
    renderApp('/entrar')

    await fillLogin(user)

    expect(await screen.findByRole('heading', { name: 'Defina a sua senha' })).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'Trocar senha' })).not.toBeInTheDocument()
  })

  it('tentar abrir o início com a troca pendente volta para a troca', async () => {
    mockApi({ 'POST /api/auth/login': () => token('t-1', 900, true), 'GET /api/me': () => json(200, pending) })
    const user = userEvent.setup()
    renderApp('/entrar')
    await fillLogin(user)
    await screen.findByRole('heading', { name: 'Defina a sua senha' })

    // o link da marca aponta para "/", rota protegida: o guarda devolve para a troca
    await user.click(screen.getByRole('link', { name: 'QueueCare' }))

    expect(await screen.findByRole('heading', { name: 'Defina a sua senha' })).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: /Olá/ })).not.toBeInTheDocument()
  })

  it('troca com sucesso usa o TOKEN NOVO e libera o início', async () => {
    const { calls } = mockApi({
      'POST /api/auth/login': () => token('t-velho', 900, true),
      'GET /api/me': (c) => json(200, c.authorization === 'Bearer t-novo' ? { ...pending, mustChangePassword: false } : pending),
      'POST /api/auth/change-password': () => token('t-novo', 900, false),
    })
    const user = userEvent.setup()
    renderApp('/entrar')
    await fillLogin(user)
    await screen.findByRole('heading', { name: 'Defina a sua senha' })

    await user.type(screen.getByLabelText('Senha atual'), PASSWORD)
    await user.type(screen.getByLabelText('Nova senha'), NEW_PASSWORD)
    await user.type(screen.getByLabelText('Repita a nova senha'), NEW_PASSWORD)
    await user.click(screen.getByRole('button', { name: 'Salvar nova senha' }))

    expect(await screen.findByRole('heading', { name: 'Olá, Oficina Central' })).toBeInTheDocument()
    const change = calls.find((c) => c.path === '/api/auth/change-password')!
    expect(change.authorization).toBe('Bearer t-velho')
    expect(change.body).toEqual({ currentPassword: PASSWORD, newPassword: NEW_PASSWORD })
    expect(calls.at(-1)?.authorization).toBe('Bearer t-novo')
  })

  it('valida no navegador (confirmação, política, igual à atual) antes de chamar a API', async () => {
    const { calls } = mockApi({ 'POST /api/auth/login': () => token('t-1', 900, true), 'GET /api/me': () => json(200, pending) })
    const user = userEvent.setup()
    renderApp('/entrar')
    await fillLogin(user)
    await screen.findByRole('heading', { name: 'Defina a sua senha' })
    const before = calls.length

    await user.type(screen.getByLabelText('Senha atual'), PASSWORD)
    await user.type(screen.getByLabelText('Nova senha'), PASSWORD)
    await user.type(screen.getByLabelText('Repita a nova senha'), 'outra')
    await user.click(screen.getByRole('button', { name: 'Salvar nova senha' }))

    expect(await screen.findByText('A nova senha deve ser diferente da atual.')).toBeInTheDocument()
    expect(screen.getByText('A confirmação não é igual à nova senha.')).toBeInTheDocument()
    expect(calls.length).toBe(before) // nada foi enviado
  })

  it('senha atual errada aparece no campo, sem derrubar a sessão', async () => {
    mockApi({
      'POST /api/auth/login': () => token('t-1', 900, true),
      'GET /api/me': () => json(200, pending),
      'POST /api/auth/change-password': () => problem(400, 'senha atual incorreta', { reason: 'INVALID_CURRENT_PASSWORD' }),
    })
    const user = userEvent.setup()
    renderApp('/entrar')
    await fillLogin(user)
    await screen.findByRole('heading', { name: 'Defina a sua senha' })

    await user.type(screen.getByLabelText('Senha atual'), WRONG_PASSWORD)
    await user.type(screen.getByLabelText('Nova senha'), NEW_PASSWORD)
    await user.type(screen.getByLabelText('Repita a nova senha'), NEW_PASSWORD)
    await user.click(screen.getByRole('button', { name: 'Salvar nova senha' }))

    expect(await screen.findByText('A senha atual não confere.')).toBeInTheDocument()
    expect(screen.getByLabelText('Senha atual')).toHaveAttribute('aria-invalid', 'true')
    expect(screen.getByRole('heading', { name: 'Defina a sua senha' })).toBeInTheDocument()
  })
})

describe('cadastro', () => {
  it('valida antes de enviar e depois entra já logado', async () => {
    const { calls } = mockApi({
      'POST /api/auth/register': () => json(201, USER),
      'POST /api/auth/login': () => token(),
      'GET /api/me': () => json(200, USER),
    })
    const user = userEvent.setup()
    renderApp('/cadastro')

    await user.click(screen.getByRole('button', { name: 'Criar conta' }))
    expect(await screen.findByText('Informe o seu nome.')).toBeInTheDocument()
    expect(screen.getByText('Informe um e-mail válido.')).toBeInTheDocument()
    expect(calls.length).toBe(0)

    await user.type(screen.getByLabelText('Nome'), 'Maria Souza')
    await user.type(screen.getByLabelText('E-mail'), 'maria@exemplo.test')
    await user.type(screen.getByLabelText('Senha'), PASSWORD)
    await user.click(screen.getByRole('button', { name: 'Criar conta' }))

    expect(await screen.findByRole('heading', { name: 'Olá, Maria Souza' })).toBeInTheDocument()
    expect(calls[0].body).toEqual({ name: 'Maria Souza', email: 'maria@exemplo.test', password: PASSWORD })
  })

  it('e-mail já cadastrado aparece no campo de e-mail', async () => {
    mockApi({ 'POST /api/auth/register': () => problem(409, 'e-mail já cadastrado') })
    const user = userEvent.setup()
    renderApp('/cadastro')

    await user.type(screen.getByLabelText('Nome'), 'Maria Souza')
    await user.type(screen.getByLabelText('E-mail'), 'maria@exemplo.test')
    await user.type(screen.getByLabelText('Senha'), PASSWORD)
    await user.click(screen.getByRole('button', { name: 'Criar conta' }))

    expect(await screen.findByText('Esse e-mail já está cadastrado.')).toBeInTheDocument()
  })
})

describe('sessão', () => {
  it('sair encerra a sessão e leva à entrada', async () => {
    mockApi({ 'POST /api/auth/login': () => token(), 'GET /api/me': () => json(200, USER) })
    const user = userEvent.setup()
    renderApp('/entrar')
    await fillLogin(user)
    await screen.findByRole('heading', { name: 'Olá, Maria Souza' })

    await user.click(screen.getByRole('button', { name: 'Sair' }))

    expect(await screen.findByRole('heading', { name: 'Entrar' })).toBeInTheDocument()
    expect(screen.queryByText('Sua sessão terminou. Entre novamente para continuar.')).not.toBeInTheDocument() // saiu por vontade própria
  })

  it('o token vencido encerra a sessão e a tela de entrada avisa', async () => {
    mockApi({ 'POST /api/auth/login': () => token('t-1', 20), 'GET /api/me': () => json(200, USER) })
    renderApp('/entrar')
    vi.useFakeTimers({ shouldAdvanceTime: true })
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime })
    await fillLogin(user)
    await screen.findByRole('heading', { name: 'Olá, Maria Souza' })

    await act(async () => {
      vi.advanceTimersByTime(16_000) // 20 s de validade menos a folga de 5 s
    })

    await waitFor(() => expect(screen.getByRole('heading', { name: 'Entrar' })).toBeInTheDocument())
    expect(screen.getByText('Sua sessão terminou. Entre novamente para continuar.')).toBeInTheDocument()
  })

  it('a tela de troca de senha voluntária mostra o aviso dos outros aparelhos', async () => {
    mockApi({ 'POST /api/auth/login': () => token(), 'GET /api/me': () => json(200, USER) })
    const user = userEvent.setup()
    renderApp('/entrar')
    await fillLogin(user)
    await screen.findByRole('heading', { name: 'Olá, Maria Souza' })

    await user.click(screen.getByRole('link', { name: 'Trocar senha' }))

    expect(await screen.findByRole('heading', { name: 'Trocar senha' })).toBeInTheDocument()
    expect(screen.getByText('Ao trocar a senha, você sai dos outros aparelhos.')).toBeInTheDocument()
  })
})

describe('início por perfil', () => {
  it.each([
    ['ADMIN', 'Criar contas de prestadores'],
    ['PROVIDER', 'No dia a dia'],
    ['ATTENDANT', 'No dia a dia'],
    ['CUSTOMER', 'No dia a dia'],
  ])('%s vê o que vem a seguir para o seu perfil e nada dos outros', async (role, expected) => {
    mockApi({ 'POST /api/auth/login': () => token(), 'GET /api/me': () => json(200, { ...USER, role }) })
    const user = userEvent.setup()
    renderApp('/entrar')
    await fillLogin(user)

    expect(await screen.findByText(expected)).toBeInTheDocument()
    expect(screen.getAllByRole('listitem').length).toBeGreaterThan(0)
  })
})
