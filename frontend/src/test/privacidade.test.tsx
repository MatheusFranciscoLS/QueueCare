import { act, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { PRIVACY_POLICY_VERSION } from '../api/privacy'
import { json, mockApi, PASSWORD, problem, renderApp, renderWithHarness, token, USER, type Call } from './helpers'

vi.mock('../pages/customer/CustomerPanel', () => ({ CustomerPanel: () => null }))
vi.mock('../pages/provider/SetupPanel', () => ({ SetupPanel: () => null }))
vi.mock('../pages/TodaySummary', () => ({ TodaySummary: () => null }))
afterEach(() => vi.unstubAllGlobals())

type Routes = Record<string, (call: Call) => Response | Promise<Response>>

describe('Política de Privacidade', () => {
  it('abre sem login, com a versão, o que o app guarda, o que NÃO faz e como exercer os direitos', async () => {
    mockApi({})
    renderApp('/privacidade')
    expect(await screen.findByRole('heading', { level: 1, name: 'Política de Privacidade' })).toBeInTheDocument()
    expect(screen.getByText(`Versão ${PRIVACY_POLICY_VERSION}`)).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'O que o app guarda' })).toBeInTheDocument()
    expect(screen.getByText(/não usa cookies de rastreamento/)).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Seus direitos e como exercê-los' })).toBeInTheDocument()
    expect(screen.getByText(/projeto de portfólio/)).toBeInTheDocument()
  })

  it('a tela de entrada e o cadastro levam à política', async () => {
    mockApi({})
    renderApp('/entrar')
    expect(await screen.findByRole('link', { name: 'Política de Privacidade' })).toHaveAttribute('href', '/privacidade')
  })

  it('o cadastro avisa que a pessoa confirma ter lido a política e envia a VERSÃO lida', async () => {
    const { calls } = mockApi({
      'POST /api/auth/register': () => json(201, USER),
      'POST /api/auth/login': () => token(),
      'GET /api/me': () => json(200, USER),
    })
    const user = userEvent.setup()
    renderApp('/cadastro')
    expect(await screen.findByText(/Ao criar a conta, você confirma que leu a/)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Política de Privacidade' })).toHaveAttribute('href', '/privacidade')
    await user.type(screen.getByLabelText('Nome'), 'Maria Souza')
    await user.type(screen.getByLabelText('E-mail'), 'maria@exemplo.test')
    await user.type(screen.getByLabelText('WhatsApp'), '(11) 91234-5678')
    await user.type(screen.getByLabelText('Senha'), PASSWORD)
    await user.click(screen.getByRole('button', { name: 'Criar conta' }))
    await waitFor(() => expect(calls.some((c) => c.path === '/api/auth/register')).toBe(true))
    expect(calls.find((c) => c.path === '/api/auth/register')?.body).toMatchObject({ privacyPolicyVersion: PRIVACY_POLICY_VERSION })
  })
})

describe('Meu perfil: meus dados', () => {
  async function openProfile(role: string, routes: Routes) {
    const { calls } = mockApi({
      'POST /api/auth/login': () => token(),
      'GET /api/me': () => json(200, { ...USER, role, name: 'Maria Souza' }),
      ...routes,
    })
    const view = renderWithHarness()
    await act(async () => view.api().login('x@exemplo.test', PASSWORD))
    await screen.findByRole('heading', { name: 'Olá, Maria Souza' })
    const user = userEvent.setup()
    await user.click(within(screen.getByRole('navigation')).getByRole('link', { name: 'Início' })) // garante o menu pronto
    act(() => view.navigate()('/perfil'))
    await screen.findByRole('heading', { level: 1, name: 'Meu perfil' })
    return { calls, user }
  }

  beforeEach(() => {
    URL.createObjectURL = vi.fn(() => 'blob:meus-dados')
    URL.revokeObjectURL = vi.fn()
  })

  it('só o tutor vê a seção; dono, atendente e admin não', async () => {
    await openProfile('PROVIDER', {})
    expect(screen.queryByRole('region', { name: 'Meus dados e privacidade' })).not.toBeInTheDocument()
  })

  it('baixa o arquivo com os dados e avisa', async () => {
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => undefined)
    const { calls, user } = await openProfile('CUSTOMER', { 'GET /api/me/export': () => json(200, { account: { name: 'Maria Souza' }, pets: [], appointments: [], waitlist: [] }) })
    await user.click(screen.getByRole('button', { name: 'Baixar meus dados' }))

    expect(await screen.findByText(/Arquivo meus-dados.json baixado/)).toBeInTheDocument()
    expect(calls.some((c) => c.path === '/api/me/export')).toBe(true)
    expect(click).toHaveBeenCalled()
    expect(URL.createObjectURL).toHaveBeenCalled()
    click.mockRestore()
  })

  it('excluir pede a senha, explica que não tem volta, mostra o erro da API e só então sai', async () => {
    let allowed = false
    const { calls, user } = await openProfile('CUSTOMER', {
      'POST /api/me/delete-account': (c) => {
        if ((c.body as { password: string }).password !== PASSWORD) return problem(400, 'senha atual incorreta')
        allowed = true
        return new Response(null, { status: 204 })
      },
    })
    await user.click(screen.getByRole('button', { name: 'Excluir minha conta' }))
    const group = screen.getByRole('group', { name: 'Confirmar exclusão da conta' })
    expect(within(group).getByText(/Isso não tem volta/)).toBeInTheDocument()

    await user.click(within(group).getByRole('button', { name: 'Excluir a conta para sempre' }))
    expect(await screen.findByText('Informe a sua senha para confirmar.')).toBeInTheDocument() // sem senha, nem chama a API
    expect(calls.some((c) => c.path === '/api/me/delete-account')).toBe(false)

    await user.type(within(group).getByLabelText('Sua senha'), 'senha-errada-123')
    await user.click(within(group).getByRole('button', { name: 'Excluir a conta para sempre' }))
    expect(await screen.findByRole('alert')).not.toBeEmptyDOMElement()
    expect(allowed).toBe(false)
    expect(screen.getByRole('heading', { level: 1, name: 'Meu perfil' })).toBeInTheDocument() // continua logada

    await user.clear(within(group).getByLabelText('Sua senha'))
    await user.type(within(group).getByLabelText('Sua senha'), PASSWORD)
    await user.click(within(group).getByRole('button', { name: 'Excluir a conta para sempre' }))
    expect(await screen.findByRole('heading', { level: 1, name: /Entrar|Bem-vind/ })).toBeInTheDocument() // a sessão acabou
    expect(allowed).toBe(true)
  })

  it('"Voltar" desiste sem chamar a API', async () => {
    const { calls, user } = await openProfile('CUSTOMER', {})
    await user.click(screen.getByRole('button', { name: 'Excluir minha conta' }))
    await user.click(screen.getByRole('button', { name: 'Voltar' }))
    expect(screen.getByRole('button', { name: 'Excluir minha conta' })).toBeInTheDocument()
    expect(calls.some((c) => c.path === '/api/me/delete-account')).toBe(false)
  })
})
