import { act, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { StrictMode } from 'react'
import { MemoryRouter } from 'react-router-dom'
import { beforeAll, describe, expect, it } from 'vitest'
import { App } from '../App'
import { AuthProvider } from '../auth/session'
import { navigationFor } from '../pages/navigation'
import { json, mockApi, PASSWORD, renderWithHarness, token, USER } from './helpers'

// O jsdom não implementa o dialog modal: um stand-in que só liga e desliga o atributo "open" (foco e inércia são do navegador; ficam para a verificação em navegador real).
beforeAll(() => {
  HTMLDialogElement.prototype.showModal = function showModal() {
    this.setAttribute('open', '')
  }
  HTMLDialogElement.prototype.close = function close() {
    this.removeAttribute('open')
    this.dispatchEvent(new Event('close'))
  }
})

async function loginAs(role: string, extra: Record<string, () => Response> = {}, overrides: Record<string, unknown> = {}) {
  mockApi({
    'POST /api/auth/login': () => token(),
    'GET /api/me': () => json(200, { ...USER, role, name: 'Maria Souza', ...overrides }),
    'GET /api/customer/pets': () => json(200, []),
    ...extra,
  })
  const view = renderWithHarness()
  await act(async () => view.api().login('x@exemplo.test', PASSWORD))
  return { view, user: userEvent.setup() }
}

describe('navegação por perfil (ADR 0018, seção 4)', () => {
  it.each([
    ['PROVIDER', ['Início', 'Agenda', 'Fila', 'Serviços', 'Expediente', 'Equipe', 'Meu negócio']],
    ['ATTENDANT', ['Início', 'Agenda', 'Fila']],
    ['CUSTOMER', ['Início', 'Agendar', 'Meus agendamentos', 'Meus pets']],
    ['ADMIN', ['Contas', 'Novo prestador']],
  ] as const)('%s tem exatamente estes itens, nesta ordem', (role, itens) => {
    expect(navigationFor(role).items.map((i) => i.label)).toEqual(itens)
  })

  it('o Início só fica ativo no endereço exato (end), para não ficar ativo em todas as telas', () => {
    expect(navigationFor('CUSTOMER').items.find((i) => i.label === 'Início')?.end).toBe(true)
  })
})

describe('casca: atalho, marcos, título e foco por rota', () => {
  it('tem o atalho para o conteúdo, o conteúdo principal com alvo do atalho e a marca que leva ao início', async () => {
    await loginAs('CUSTOMER')
    await screen.findByRole('heading', { name: 'Olá, Maria Souza' })
    expect(screen.getByRole('link', { name: 'Pular para o conteúdo' })).toHaveAttribute('href', '#conteudo')
    const main = screen.getByRole('main')
    expect(main).toHaveAttribute('id', 'conteudo')
    expect(main).toHaveAttribute('tabindex', '-1')
    expect(screen.getByRole('link', { name: 'Pelo & Ponto' })).toHaveAttribute('href', '/')
  })

  it('o título da página acompanha o h1 e, ao trocar de rota, o foco vai para o título da nova tela', async () => {
    const { user } = await loginAs('CUSTOMER')
    await screen.findByRole('heading', { name: 'Olá, Maria Souza' })
    await waitFor(() => expect(document.title).toBe('Olá, Maria Souza · Pelo & Ponto'))

    await user.click(within(screen.getByRole('navigation', { name: 'Menu do cliente' })).getByRole('link', { name: 'Meus pets' }))
    const heading = await screen.findByRole('heading', { name: 'Meus pets' })
    await waitFor(() => expect(heading).toHaveFocus())
    expect(document.title).toBe('Meus pets · Pelo & Ponto')
  })

  it('a conta mostra o perfil, "Trocar senha" e "Sair"; quem precisa trocar a senha não vê o menu nem o botão "Menu"', async () => {
    await loginAs('CUSTOMER', {}, { mustChangePassword: true })
    await screen.findByRole('heading', { name: 'Defina a sua senha' })
    expect(screen.queryByRole('navigation')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Menu' })).not.toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'Trocar senha' })).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Sair' })).toBeInTheDocument()
  })
})

describe('foco por rota em StrictMode (o React repete o efeito em desenvolvimento)', () => {
  it('na primeira carga o foco NÃO vai para o título; ao entrar (troca real de rota), vai', async () => {
    mockApi({ 'POST /api/auth/login': () => token(), 'GET /api/me': () => json(200, { ...USER, role: 'CUSTOMER', name: 'Maria Souza' }) })
    const user = userEvent.setup()
    render(
      <StrictMode>
        <MemoryRouter initialEntries={['/entrar']}>
          <AuthProvider>
            <App />
          </AuthProvider>
        </MemoryRouter>
      </StrictMode>,
    )
    const entrar = await screen.findByRole('heading', { name: 'Entrar' })
    expect(document.title).toBe('Entrar · Pelo & Ponto')
    expect(entrar).not.toHaveFocus()
    expect(document.body).toHaveFocus()

    await user.type(screen.getByLabelText('E-mail'), 'x@exemplo.test')
    await user.type(screen.getByLabelText('Senha'), PASSWORD)
    await user.click(screen.getByRole('button', { name: 'Entrar' }))
    const inicio = await screen.findByRole('heading', { name: 'Olá, Maria Souza' })
    await waitFor(() => expect(inicio).toHaveFocus())
  })
})

describe('tema compartilhado entre a barra lateral e a gaveta', () => {
  it('mudar o tema na gaveta atualiza também o botão da barra lateral, e o contrário', async () => {
    const { user } = await loginAs('CUSTOMER')
    await screen.findByRole('heading', { name: 'Olá, Maria Souza' })
    const lateral = () => screen.getAllByRole('button', { name: /^Tema:/ })[0]
    expect(lateral()).toHaveTextContent('Tema: sistema')

    await user.click(screen.getByRole('button', { name: 'Menu' }))
    const gaveta = screen.getByRole('dialog', { name: 'Menu' })
    await user.click(within(gaveta).getByRole('button', { name: 'Tema: sistema' }))
    await waitFor(() => expect(lateral()).toHaveTextContent('Tema: claro'))
    expect(within(gaveta).getByRole('button', { name: 'Tema: claro' })).toBeInTheDocument()
    expect(document.documentElement).toHaveAttribute('data-theme', 'light')

    await user.click(within(gaveta).getByRole('button', { name: 'Fechar' }))
    await user.click(lateral())
    await waitFor(() => expect(lateral()).toHaveTextContent('Tema: escuro'))
    await user.click(screen.getByRole('button', { name: 'Menu' }))
    expect(within(screen.getByRole('dialog', { name: 'Menu' })).getByRole('button', { name: 'Tema: escuro' })).toBeInTheDocument()
    document.documentElement.removeAttribute('data-theme')
    window.localStorage.clear()
  })
})

describe('gaveta do celular', () => {
  it('abre pelo botão Menu com o menu e a conta, e fecha pelo botão Fechar', async () => {
    const { user } = await loginAs('CUSTOMER')
    await screen.findByRole('heading', { name: 'Olá, Maria Souza' })
    expect(screen.queryByRole('navigation', { name: 'Menu do cliente (celular)' })).not.toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Menu' }))
    const drawer = screen.getByRole('dialog', { name: 'Menu' })
    expect(within(drawer).getByRole('navigation', { name: 'Menu do cliente (celular)' })).toBeInTheDocument()
    expect(within(drawer).getByRole('button', { name: 'Sair' })).toBeInTheDocument()

    await user.click(within(drawer).getByRole('button', { name: 'Fechar' }))
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
  })

  it('escolher um item fecha a gaveta e leva à tela, com o foco no título', async () => {
    const { user } = await loginAs('CUSTOMER')
    await screen.findByRole('heading', { name: 'Olá, Maria Souza' })
    await user.click(screen.getByRole('button', { name: 'Menu' }))
    await user.click(within(screen.getByRole('dialog', { name: 'Menu' })).getByRole('link', { name: 'Meus pets' }))

    const heading = await screen.findByRole('heading', { name: 'Meus pets' })
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    await waitFor(() => expect(heading).toHaveFocus())
  })

  it('o clique no fundo (fora do painel) fecha a gaveta', async () => {
    const { user } = await loginAs('CUSTOMER')
    await screen.findByRole('heading', { name: 'Olá, Maria Souza' })
    await user.click(screen.getByRole('button', { name: 'Menu' }))
    const drawer = screen.getByRole('dialog', { name: 'Menu' })
    await user.click(drawer) // o próprio <dialog> é o fundo; clicar dentro do painel acerta os filhos
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
  })
})
