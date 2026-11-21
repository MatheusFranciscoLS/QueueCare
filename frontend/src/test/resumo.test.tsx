import { act, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { addDays, todayIn } from '../api/dates'
import { comeBackMessage, periodRange, type LapsedCustomer, type ReportSummary } from '../api/reports'
import { json, mockApi, PASSWORD, problem, renderWithHarness, token, USER, type Call } from './helpers'

vi.mock('../pages/customer/CustomerPanel', () => ({ CustomerPanel: () => null }))
vi.mock('../pages/provider/SetupPanel', () => ({ SetupPanel: () => null }))
vi.mock('../pages/TodaySummary', () => ({ TodaySummary: () => null }))
afterEach(() => vi.unstubAllGlobals())

type Routes = Record<string, (call: Call) => Response | Promise<Response>>

const ZONE = 'America/Sao_Paulo'
const today = todayIn(ZONE)

const summary = (extra: Partial<ReportSummary> = {}): ReportSummary => ({
  from: addDays(today, -6),
  to: today,
  counts: { completed: 12, noShow: 2, cancelled: 1, scheduled: 4, inProgress: 1 },
  amounts: { completedCents: 84000, expectedCents: 31000, depositsReceivedCents: 6000 },
  byService: [
    { service: 'Banho', completed: 8, completedCents: 52000 },
    { service: 'Tosa', completed: 4, completedCents: 32000 },
  ],
  byDay: [
    { date: addDays(today, -1), completed: 5, noShow: 1, cancelled: 0 },
    { date: today, completed: 7, noShow: 1, cancelled: 1 },
  ],
  topNoShows: [{ customerId: 'c-1', name: 'Pedro Alves', noShows: 2 }],
  ...extra,
})

const lapsed = (): LapsedCustomer[] => [{ customerId: 'c-2', name: 'Joana Lima', phone: '5511987654321', lastVisitAt: '2026-08-01T13:00:00Z', daysSince: 112 }]

describe('resumo (funções)', () => {
  it('calcula o período em dias do estúdio: hoje, 7 dias com hoje incluso, 30 dias e o mês até hoje', () => {
    expect(periodRange('today', '2026-11-21')).toEqual({ from: '2026-11-21', to: '2026-11-21' })
    expect(periodRange('7d', '2026-11-21')).toEqual({ from: '2026-11-15', to: '2026-11-21' })
    expect(periodRange('30d', '2026-11-21')).toEqual({ from: '2026-10-23', to: '2026-11-21' })
    expect(periodRange('month', '2026-11-21')).toEqual({ from: '2026-11-01', to: '2026-11-21' })
    expect(periodRange('7d', '2026-11-03')).toEqual({ from: '2026-10-28', to: '2026-11-03' }) // atravessa o mês
  })

  it('a mensagem para chamar de volta usa o primeiro nome e o nome do estúdio', () => {
    expect(comeBackMessage('  Joana Lima ', 'Estúdio Bem-te-vi')).toBe(
      'Olá, Joana! Aqui é do Estúdio Bem-te-vi. Faz um tempinho que não vemos você e o seu pet por aqui. Quer marcar um horário?',
    )
  })
})

async function openResumo(role: string, routes: Routes) {
  const { calls } = mockApi({
    'POST /api/auth/login': () => token(),
    'GET /api/me': () => json(200, { ...USER, role, name: 'Maria Souza' }),
    'GET /api/agenda/workplace': () => json(200, { providerId: 'p-1', businessName: 'Estúdio Bem-te-vi', timeZone: ZONE, cancellationDeadlineMinutes: 720 }),
    ...routes,
  })
  const view = renderWithHarness()
  await act(async () => view.api().login('x@exemplo.test', PASSWORD))
  await screen.findByRole('heading', { name: 'Olá, Maria Souza' })
  const user = userEvent.setup()
  return { calls, user, view }
}

describe('Resumo do estúdio', () => {
  const SUMMARY_7D = `GET /api/provider/reports/summary?from=${addDays(today, -6)}&to=${today}`
  const LAPSED_60 = 'GET /api/provider/reports/lapsed?days=60'

  async function goToResumo(routes: Routes) {
    const ctx = await openResumo('PROVIDER', routes)
    await ctx.user.click(within(screen.getByRole('navigation')).getByRole('link', { name: 'Resumo' }))
    await screen.findByRole('heading', { level: 1, name: 'Resumo' })
    return ctx
  }

  it('só o dono tem o item "Resumo" no menu; o atendente não', async () => {
    await openResumo('ATTENDANT', {})
    expect(within(screen.getByRole('navigation')).queryByRole('link', { name: 'Resumo' })).not.toBeInTheDocument()
  })

  it('abre nos últimos 7 dias com atendimentos, valores com nome honesto e o aviso de que NÃO é faturamento', async () => {
    await goToResumo({ [SUMMARY_7D]: () => json(200, summary()), [LAPSED_60]: () => json(200, []) })

    expect(await screen.findByRole('heading', { name: 'Atendimentos' })).toBeInTheDocument()
    const stats = screen.getAllByRole('definition').map((d) => d.textContent)
    expect(stats).toEqual(expect.arrayContaining(['12', '2', '1', '4']))
    expect(screen.getByText('Atendimentos concluídos').nextSibling).toHaveTextContent('R$ 840,00')
    expect(screen.getByText('Ainda por concluir (previsto)').nextSibling).toHaveTextContent('R$ 310,00')
    expect(screen.getByText('Sinais Pix recebidos').nextSibling).toHaveTextContent('R$ 60,00')
    expect(screen.getByText(/Não são faturamento/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '7 dias' })).toHaveAttribute('aria-pressed', 'true')
  })

  it('mostra por serviço, por dia (em tabela) e quem mais faltou', async () => {
    await goToResumo({ [SUMMARY_7D]: () => json(200, summary()), [LAPSED_60]: () => json(200, []) })

    const byService = await screen.findByRole('table', { name: 'Atendimentos concluídos por serviço' })
    expect(within(byService).getByRole('row', { name: /Banho 8 R\$ 520,00/ })).toBeInTheDocument()
    expect(within(byService).getByRole('row', { name: /Tosa 4 R\$ 320,00/ })).toBeInTheDocument()

    const days = screen.getByRole('table', { name: 'Atendimentos por dia', hidden: true })
    expect(within(days).getAllByRole('row', { hidden: true })).toHaveLength(3) // cabeçalho + 2 dias
    expect(screen.getByText('Pedro Alves')).toBeInTheDocument()
    expect(screen.getByText(/2 faltas/)).toBeInTheDocument()
  })

  it('trocar o período refaz a consulta com as datas do estúdio', async () => {
    const { calls, user } = await goToResumo({
      [SUMMARY_7D]: () => json(200, summary()),
      [`GET /api/provider/reports/summary?from=${today}&to=${today}`]: () => json(200, summary({ counts: { completed: 3, noShow: 0, cancelled: 0, scheduled: 0, inProgress: 0 } })),
      [LAPSED_60]: () => json(200, []),
    })
    await screen.findByRole('heading', { name: 'Atendimentos' })
    await user.click(screen.getByRole('button', { name: 'Hoje' }))

    expect(await screen.findByText('3')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Hoje' })).toHaveAttribute('aria-pressed', 'true')
    expect(calls.some((c) => c.path === `/api/provider/reports/summary?from=${today}&to=${today}`)).toBe(true)
  })

  it('período sem atendimento avisa e não desenha gráfico nem tabelas vazias', async () => {
    const empty = summary({ counts: { completed: 0, noShow: 0, cancelled: 0, scheduled: 0, inProgress: 0 }, byService: [], byDay: [], topNoShows: [] })
    await goToResumo({ [SUMMARY_7D]: () => json(200, empty), [LAPSED_60]: () => json(200, []) })
    expect(await screen.findByText('Nenhum atendimento neste período.')).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'Por serviço' })).not.toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'Por dia' })).not.toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'Quem mais faltou' })).not.toBeInTheDocument()
  })

  it('clientes que sumiram: nome, quanto tempo, e WhatsApp com a mensagem pronta; trocar o prazo refaz a lista', async () => {
    const { calls, user } = await goToResumo({
      [SUMMARY_7D]: () => json(200, summary()),
      [LAPSED_60]: () => json(200, lapsed()),
      'GET /api/provider/reports/lapsed?days=90': () => json(200, []),
    })
    const item = (await screen.findByText('Joana Lima')).closest('li') as HTMLElement
    expect(item).toHaveTextContent('última visita há 112 dias')
    const link = within(item).getByRole('link', { name: /Chamar no WhatsApp/ })
    const href = decodeURIComponent(link.getAttribute('href') ?? '')
    expect(href).toContain('https://wa.me/5511987654321')
    expect(href).toContain('Olá, Joana! Aqui é do Estúdio Bem-te-vi.')

    await user.click(screen.getByRole('button', { name: '90 dias' }))
    expect(await screen.findByText('Ninguém sumiu por mais de 90 dias. Bom sinal.')).toBeInTheDocument()
    expect(calls.some((c) => c.path === '/api/provider/reports/lapsed?days=90')).toBe(true)
  })

  it('erro na leitura mostra a mensagem e continua com o restante da tela', async () => {
    await goToResumo({ [SUMMARY_7D]: () => problem(500, 'erro'), [LAPSED_60]: () => json(200, []) })
    expect(await screen.findByRole('alert')).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Clientes que sumiram' })).toBeInTheDocument()
  })
})
