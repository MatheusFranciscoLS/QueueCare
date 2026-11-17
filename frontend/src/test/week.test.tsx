import { act, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { addDays, shortWeekday, startOfWeek, todayIn, weekTitle } from '../api/dates'
import { json, mockApi, PASSWORD, problem, renderWithHarness, token, USER, type Call } from './helpers'

vi.mock('../pages/TodaySummary', () => ({ TodaySummary: () => null }))
afterEach(() => vi.unstubAllGlobals())

const WORKPLACE = { providerId: 'p-1', businessName: 'Estúdio Bem-te-vi', timeZone: 'America/Sao_Paulo' }
const today = todayIn(WORKPLACE.timeZone)
const monday = startOfWeek(today)

const slot = (id: string, date: string, hourZ: string, status: string, extra: Record<string, unknown> = {}) => ({
  id,
  startsAt: `${date}T${hourZ}:00Z`,
  endsAt: `${date}T${String(Number(hourZ.slice(0, 2)) + 1).padStart(2, '0')}${hourZ.slice(2)}:00Z`, // uma hora de duração
  status,
  serviceId: 's-1',
  serviceName: 'Banho',
  customerId: 'c-1',
  customerName: 'Marina Souza',
  ...extra,
})

type Handler = (call: Call) => Response | Promise<Response>

async function openWeek(week: Handler, routes: Record<string, Handler> = {}) {
  const { calls } = mockApi({
    'POST /api/auth/login': () => token(),
    'GET /api/me': () => json(200, { ...USER, role: 'PROVIDER', name: 'Helena Prado' }),
    'GET /api/agenda/workplace': () => json(200, WORKPLACE),
    [`GET /api/agenda/week?start=${monday}`]: week,
    ...routes,
  })
  const view = renderWithHarness()
  await act(async () => view.api().login('x@exemplo.test', PASSWORD))
  await screen.findByRole('heading', { name: 'Olá, Helena Prado' })
  const user = userEvent.setup()
  await user.click(within(screen.getByRole('navigation', { name: 'Menu do prestador' })).getByRole('link', { name: 'Agenda' }))
  await user.click(await screen.findByRole('button', { name: 'Semana' }))
  return { calls, user }
}

describe('datas da semana', () => {
  it.each([
    ['2026-11-16', '2026-11-16'],
    ['2026-11-17', '2026-11-16'],
    ['2026-11-22', '2026-11-16'],
    ['2026-11-23', '2026-11-23'],
    ['2026-01-01', '2025-12-29'],
  ])('startOfWeek(%s) = %s', (date, expected) => {
    expect(startOfWeek(date)).toBe(expected)
  })

  it('título da semana: mesmo mês, meses diferentes e anos diferentes', () => {
    expect(weekTitle('2026-11-16')).toBe('16 a 22 de novembro de 2026')
    expect(weekTitle('2026-11-30')).toBe('30 de novembro a 6 de dezembro de 2026')
    expect(weekTitle('2025-12-29')).toBe('29 de dezembro de 2025 a 4 de janeiro de 2026')
  })

  it('dia da semana abreviado com inicial maiúscula e sem ponto', () => {
    expect(shortWeekday('2026-11-16')).toBe('Seg')
    expect(shortWeekday('2026-11-22')).toBe('Dom')
  })
})

describe('agenda: semana', () => {
  it('mostra os 7 dias e agrupa pelo dia do NEGÓCIO: 02:00Z ainda é o dia anterior em São Paulo', async () => {
    await openWeek(() =>
      json(200, [
        slot('a-1', monday, '13:00', 'COMPLETED', { pet: { id: 'x', name: 'Mel', species: 'CAT', breed: 'Siamês', size: 'SMALL', notes: null } }),
        slot('a-2', addDays(monday, 1), '02:00', 'SCHEDULED', { customerName: 'Carla Mendes' }), // 23:00 de segunda em São Paulo
      ]),
    )
    const week = await screen.findByRole('region', { name: /^Semana de / })
    const dias = within(week).getAllByRole('region')
    expect(dias).toHaveLength(7)
    const segunda = dias[0]
    expect(within(segunda).getByRole('heading', { level: 3 })).toHaveTextContent('Seg')
    expect(within(segunda).getByText('10:00–11:00')).toBeInTheDocument() // 13:00Z = 10:00 em São Paulo, uma hora
    expect(within(segunda).getByText('Mel')).toBeInTheDocument() // o pet em destaque, quando há
    expect(within(segunda).getByText('Concluído')).toBeInTheDocument() // o estado também em texto
    expect(within(segunda).getByText('23:00–00:00')).toBeInTheDocument() // caiu na segunda, não na terça
    expect(within(segunda).getByText('Carla Mendes')).toBeInTheDocument() // sem pet, o tutor
    expect(within(dias[1]).getByText('Sem agendamentos')).toBeInTheDocument()
  })

  it('"Próxima semana", "Semana anterior" e "Esta semana" pedem a semana certa', async () => {
    const { calls, user } = await openWeek(() => json(200, []), {
      [`GET /api/agenda/week?start=${addDays(monday, 7)}`]: () => json(200, []),
      [`GET /api/agenda/week?start=${addDays(monday, -7)}`]: () => json(200, []),
    })
    await screen.findByText('Nenhum agendamento nesta semana.')
    await user.click(screen.getByRole('button', { name: 'Próxima semana' }))
    await waitFor(() => expect(calls.some((c) => c.path === `/api/agenda/week?start=${addDays(monday, 7)}`)).toBe(true))
    await user.click(screen.getByRole('button', { name: 'Esta semana' }))
    await user.click(screen.getByRole('button', { name: 'Semana anterior' }))
    await waitFor(() => expect(calls.some((c) => c.path === `/api/agenda/week?start=${addDays(monday, -7)}`)).toBe(true))
    expect(screen.getByRole('heading', { level: 2 })).toHaveTextContent(`Semana de ${weekTitle(addDays(monday, -7))}`)
  })

  it('abrir um dia leva à agenda daquele dia (visão Dia)', async () => {
    const day = addDays(monday, 2)
    const { user } = await openWeek(() => json(200, [slot('a-1', day, '15:00', 'SCHEDULED')]), {
      [`GET /api/agenda?date=${day}`]: () => json(200, [slot('a-1', day, '15:00', 'SCHEDULED')]),
    })
    const week = await screen.findByRole('region', { name: /^Semana de / })
    await user.click(within(week).getByRole('button', { name: /^Qua .*abrir o dia/ }))
    expect(await screen.findByRole('button', { name: 'Cancelar agendamento' })).toBeInTheDocument() // a linha do dia, com as ações
    expect(screen.getByRole('button', { name: 'Dia' })).toHaveAttribute('aria-pressed', 'true')
    expect(screen.getByRole('button', { name: 'Semana' })).toHaveAttribute('aria-pressed', 'false')
  })

  it('falha ao carregar mostra o motivo; a visão selecionada é anunciada com aria-pressed', async () => {
    await openWeek(() => problem(500, 'Erro inesperado.'))
    expect(await screen.findByRole('alert')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Semana' })).toHaveAttribute('aria-pressed', 'true')
    expect(screen.getByRole('button', { name: 'Dia' })).toHaveAttribute('aria-pressed', 'false')
  })
})
