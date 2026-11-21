import { act, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { publicPolicyText } from '../api/policy'
import { json, mockApi, PASSWORD, problem, renderApp, renderWithHarness, token, USER, type Call } from './helpers'

vi.mock('../pages/TodaySummary', () => ({ TodaySummary: () => null }))
vi.mock('../pages/customer/CustomerPanel', () => ({ CustomerPanel: () => null }))
vi.mock('../pages/provider/SetupPanel', () => ({ SetupPanel: () => null }))
afterEach(() => vi.unstubAllGlobals())

type Routes = Record<string, (call: Call) => Response | Promise<Response>>

const STUDIO = {
  slug: 'estudio-bem-te-vi',
  businessName: 'Estúdio Bem-te-vi',
  timeZone: 'America/Sao_Paulo',
  cancellationDeadlineMinutes: 720,
  services: [
    { name: 'Banho', durationMinutes: 45, durationVaries: true },
    { name: 'Hidratação', durationMinutes: 30, durationVaries: false },
  ],
}

describe('política em frase (função)', () => {
  it('diz até quando cancelar de graça, e prazo zero é "até o horário marcado"', () => {
    expect(publicPolicyText(720)).toBe('Você pode cancelar ou remarcar de graça até 12 h antes do horário marcado. Depois disso, é só falar com o estúdio.')
    expect(publicPolicyText(1440)).toBe('Você pode cancelar ou remarcar de graça até 1 dia antes do horário marcado. Depois disso, é só falar com o estúdio.')
    expect(publicPolicyText(0)).toBe('Você pode cancelar ou remarcar de graça até o horário marcado. Depois disso, é só falar com o estúdio.')
  })
})

describe('página pública do estúdio (visitante)', () => {
  const open = (routes: Routes, path = '/p/estudio-bem-te-vi') => {
    const mocked = mockApi({ 'POST /api/auth/refresh': () => problem(401, 'sem sessão'), ...routes })
    renderApp(path)
    return mocked
  }

  it('mostra o nome, os serviços ("a partir de" quando varia), a política e os dois caminhos para agendar, sem login', async () => {
    open({ 'GET /api/public/studios/estudio-bem-te-vi': () => json(200, STUDIO) })
    expect(await screen.findByRole('heading', { level: 1, name: 'Estúdio Bem-te-vi' })).toBeInTheDocument()
    const rows = screen.getAllByRole('listitem')
    expect(rows[0]).toHaveTextContent('Banho')
    expect(rows[0]).toHaveTextContent('a partir de 45 min')
    expect(rows[1]).toHaveTextContent('Hidratação')
    expect(rows[1]).toHaveTextContent('30 min')
    expect(rows[1]).not.toHaveTextContent('a partir de')
    expect(screen.getByText('Você pode cancelar ou remarcar de graça até 12 h antes do horário marcado. Depois disso, é só falar com o estúdio.')).toBeInTheDocument()
    // o chamado para agendar aparece no topo e de novo no fim da página
    const enter = screen.getAllByRole('link', { name: 'Entrar para agendar' })
    expect(enter).toHaveLength(2)
    for (const l of enter) expect(l).toHaveAttribute('href', '/entrar')
    for (const l of screen.getAllByRole('link', { name: 'Criar conta de cliente' })) expect(l).toHaveAttribute('href', '/cadastro')
  })

  it('estúdio sem serviços avisa, sem lista vazia', async () => {
    open({ 'GET /api/public/studios/estudio-bem-te-vi': () => json(200, { ...STUDIO, services: [] }) })
    expect(await screen.findByText('Este estúdio ainda não cadastrou serviços.')).toBeInTheDocument()
  })

  it('endereço inexistente ou não publicado: a mesma mensagem, com caminho de volta', async () => {
    open({ 'GET /api/public/studios/nao-existe': () => problem(404, 'estúdio não encontrado', { reason: 'STUDIO_NOT_FOUND' }) }, '/p/nao-existe')
    expect(await screen.findByRole('heading', { level: 1, name: 'Estúdio não encontrado' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Ir para o início' })).toBeInTheDocument()
  })

  it('falha de rede mostra o erro, sem se passar por "não encontrado"', async () => {
    open({ 'GET /api/public/studios/estudio-bem-te-vi': () => problem(500, 'erro') })
    expect(await screen.findByRole('alert')).toHaveTextContent('Algo deu errado no servidor')
    expect(screen.queryByRole('heading', { name: 'Estúdio não encontrado' })).not.toBeInTheDocument()
  })
})

describe('página pública para quem já tem conta', () => {
  it('o tutor logado vê "Agendar" em vez de entrar ou cadastrar', async () => {
    mockApi({
      'POST /api/auth/login': () => token(),
      'GET /api/me': () => json(200, { ...USER, role: 'CUSTOMER', name: 'Maria Souza' }),
      'GET /api/public/studios/estudio-bem-te-vi': () => json(200, STUDIO),
    })
    const view = renderWithHarness('/p/estudio-bem-te-vi')
    await act(async () => view.api().login('x@exemplo.test', PASSWORD))
    expect(await screen.findByRole('heading', { level: 1, name: 'Estúdio Bem-te-vi' })).toBeInTheDocument()
    const ctas = screen.getAllByRole('link', { name: 'Agendar' }).filter((l) => l.classList.contains('button'))
    expect(ctas).toHaveLength(2) // topo e fim da página (o menu também tem um link "Agendar")
    for (const l of ctas) expect(l).toHaveAttribute('href', '/agendar')
    expect(screen.queryByRole('link', { name: 'Entrar para agendar' })).not.toBeInTheDocument()
  })
})

describe('Meu negócio: página pública (dono)', () => {
  const BUSINESS = { id: 'p-1', businessName: 'Estúdio', timeZone: 'America/Sao_Paulo', minimumNoticeMinutes: 0, cancellationDeadlineMinutes: 720, phone: '5511912345678', depositRule: 'NEVER' }
  const open = async (page: object, extra: Routes = {}) => {
    mockApi({
      'POST /api/auth/login': () => token(),
      'GET /api/me': () => json(200, { ...USER, role: 'PROVIDER', name: 'Maria Souza' }),
      'GET /api/provider': () => json(200, BUSINESS),
      'GET /api/provider/public-page': () => json(200, page),
      ...extra,
    })
    const view = renderWithHarness()
    await act(async () => view.api().login('x@exemplo.test', PASSWORD))
    await screen.findByRole('heading', { name: 'Olá, Maria Souza' })
    const user = userEvent.setup()
    await user.click(within(screen.getByRole('navigation', { name: 'Menu do prestador' })).getByRole('link', { name: 'Meu negócio' }))
    await screen.findByRole('heading', { name: 'Meu negócio' })
    return user
  }

  it('começa sem endereço e despublicada, e explica o que o link mostra', async () => {
    await open({ slug: null, published: false })
    const section = await screen.findByRole('region', { name: 'Página pública' })
    expect(within(section).getByLabelText('Endereço do estúdio')).toHaveValue('')
    expect(within(section).getByLabelText('Publicar a página (qualquer pessoa com o link poderá ver)')).not.toBeChecked()
    expect(within(section).getByText(/sem pedir login/)).toBeInTheDocument()
    expect(within(section).queryByRole('link')).not.toBeInTheDocument() // sem link enquanto não publicada
  })

  it('salva o endereço em minúsculas, publica e mostra o link para abrir e copiar', async () => {
    let page: { slug: string | null; published: boolean } = { slug: null, published: false }
    const calls: Call[] = []
    const user = await open(page, {
      'GET /api/provider/public-page': () => json(200, page),
      'PUT /api/provider/public-page': (call) => {
        calls.push(call)
        page = call.body as typeof page
        return json(200, page)
      },
    })
    const section = await screen.findByRole('region', { name: 'Página pública' })
    await user.type(within(section).getByLabelText('Endereço do estúdio'), 'Estudio-Bem-Te-Vi')
    await user.click(within(section).getByLabelText('Publicar a página (qualquer pessoa com o link poderá ver)'))
    await user.click(within(section).getByRole('button', { name: 'Salvar página pública' }))

    expect(await screen.findByText('Página pública salva.')).toBeVisible()
    expect(calls[0].body).toEqual({ slug: 'estudio-bem-te-vi', published: true })
    // (o formulário é refeito com o que o servidor devolveu: o elemento antigo saiu da tela, então a busca é nova)
    const link = await screen.findByRole('link', { name: /\/p\/estudio-bem-te-vi$/ })
    expect(link).toHaveAttribute('target', '_blank')
    expect(link.getAttribute('rel')).toContain('noopener')
    expect(screen.getByRole('button', { name: 'Copiar link' })).toBeInTheDocument()
  })

  it('publicar sem endereço ou com endereço inválido não é enviado', async () => {
    const user = await open({ slug: null, published: false })
    const section = await screen.findByRole('region', { name: 'Página pública' })
    await user.click(within(section).getByLabelText('Publicar a página (qualquer pessoa com o link poderá ver)'))
    await user.click(within(section).getByRole('button', { name: 'Salvar página pública' }))
    expect(await within(section).findByText('Escolha um endereço para publicar a página.')).toBeInTheDocument()

    await user.type(within(section).getByLabelText('Endereço do estúdio'), 'a b')
    await user.click(within(section).getByRole('button', { name: 'Salvar página pública' }))
    expect(await within(section).findByText(/Use de 3 a 40 letras minúsculas/)).toBeInTheDocument()
  })

  it('endereço já em uso por outro estúdio mostra o motivo', async () => {
    const user = await open({ slug: null, published: false }, { 'PUT /api/provider/public-page': () => problem(409, 'endereço em uso', { reason: 'SLUG_TAKEN' }) })
    const section = await screen.findByRole('region', { name: 'Página pública' })
    await user.type(within(section).getByLabelText('Endereço do estúdio'), 'estudio-alfa')
    await user.click(within(section).getByRole('button', { name: 'Salvar página pública' }))
    expect(await within(section).findByText('Esse endereço já está em uso por outro estúdio. Escolha outro.')).toBeInTheDocument()
    await waitFor(() => expect(within(section).getByRole('button', { name: 'Salvar página pública' })).toBeEnabled())
  })
})
