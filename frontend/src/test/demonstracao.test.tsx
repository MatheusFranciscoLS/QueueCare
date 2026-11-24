import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { json, mockApi, problem, renderApp, token, USER } from './helpers'

beforeEach(() => vi.useRealTimers())
afterEach(() => {
  vi.unstubAllGlobals()
  vi.unstubAllEnvs()
})

const DEMO_USER = { ...USER, name: 'Marina Souza', email: 'tutora@demo.invalid' }

/** Demonstração pública (ADR 0041): entrada com um clique, só no site publicado, com aviso permanente de que os dados são fictícios. */
describe('demonstração pública', () => {
  it('sem VITE_DEMO (desenvolvimento, testes, outros ambientes) a tela de entrada não oferece a demonstração', async () => {
    mockApi({})
    renderApp('/entrar')
    expect(await screen.findByRole('heading', { name: 'Entrar' })).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'Conhecer sem cadastro' })).not.toBeInTheDocument()
  })

  it('com VITE_DEMO oferece os três perfis e nenhum é o de administrador', async () => {
    vi.stubEnv('VITE_DEMO', 'true')
    mockApi({})
    renderApp('/entrar')
    expect(await screen.findByRole('heading', { name: 'Conhecer sem cadastro' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Entrar como dona do estúdio' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Entrar como atendente' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Entrar como tutora (cliente)' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /administrador/i })).not.toBeInTheDocument()
  })

  it('o clique entra sem senha, mostra o aviso de demonstração e esconde a troca de senha', async () => {
    vi.stubEnv('VITE_DEMO', 'true')
    const { calls } = mockApi({
      'POST /api/auth/demo': () => token('t-demo'),
      'GET /api/me': () => json(200, DEMO_USER),
    })
    const user = userEvent.setup()
    renderApp('/entrar')

    await user.click(await screen.findByRole('button', { name: 'Entrar como tutora (cliente)' }))

    expect(await screen.findByRole('heading', { name: 'Olá, Marina Souza' })).toBeInTheDocument()
    expect(calls[0].body).toEqual({ profile: 'CUSTOMER' })
    expect(calls[1].authorization).toBe('Bearer t-demo')
    expect(screen.getByRole('note')).toHaveTextContent('Demonstração: tudo aqui é fictício')
    expect(screen.queryByRole('link', { name: 'Trocar senha' })).not.toBeInTheDocument()
    expect(window.localStorage.length).toBe(0)
  })

  it('uma conta de verdade não vê o aviso e continua com a troca de senha', async () => {
    mockApi({ 'POST /api/auth/login': () => token('t-1'), 'GET /api/me': () => json(200, USER) })
    const user = userEvent.setup()
    renderApp('/entrar')
    await user.type(await screen.findByLabelText('E-mail'), USER.email)
    await user.type(screen.getByLabelText('Senha'), 'senha-forte-123')
    await user.click(screen.getByRole('button', { name: 'Entrar' }))

    expect(await screen.findByRole('heading', { name: 'Olá, Maria Souza' })).toBeInTheDocument()
    expect(screen.queryByRole('note')).not.toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Trocar senha' })).toBeInTheDocument()
  })

  it('demonstração desligada no servidor: explica o erro e deixa a tela de entrada como está', async () => {
    vi.stubEnv('VITE_DEMO', 'true')
    mockApi({ 'POST /api/auth/demo': () => problem(404, 'Not Found') })
    const user = userEvent.setup()
    renderApp('/entrar')

    await user.click(await screen.findByRole('button', { name: 'Entrar como atendente' }))

    expect(await screen.findByRole('alert')).not.toBeEmptyDOMElement()
    expect(screen.getByRole('heading', { name: 'Entrar' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Entrar como atendente' })).toBeEnabled()
  })
})
