import { act, fireEvent, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { addDays, todayIn } from '../api/dates'
import { json, mockApi, PASSWORD, problem, renderWithHarness, token, USER, type Call, openMore } from './helpers'

// O resumo da tela inicial tem testes próprios; aqui ele sairia do caminho das contagens de chamadas à agenda.
vi.mock('../pages/TodaySummary', () => ({ TodaySummary: () => null }))

afterEach(() => vi.unstubAllGlobals())

type Routes = Record<string, (call: Call) => Response | Promise<Response>>

const ZONE = 'America/Sao_Paulo'
const WORKPLACE = { providerId: 'prov-1', businessName: 'Estúdio Pelo e Ponto', timeZone: ZONE }
const today = todayIn(ZONE)
const tomorrow = addDays(today, 1)
const DAY = `GET /api/agenda?date=${today}`
// 15:00Z = 12:00 em São Paulo
const item = (overrides: Record<string, unknown> = {}) => ({
  id: 'a-1',
  startsAt: `${today}T15:00:00Z`,
  endsAt: `${today}T16:00:00Z`,
  status: 'SCHEDULED',
  serviceId: 's-1',
  serviceName: 'Banho e tosa',
  customerId: 'c-1',
  customerName: 'Maria Souza',
  pet: { name: 'Rex', species: 'DOG', breed: null, size: null, notes: null },
  ...overrides,
})
const slotsPath = (date: string) => `/api/providers/prov-1/slots?serviceId=s-1&date=${date}&excludeAppointmentId=a-1`
const slot = (date: string, hhmm: string) => ({ localStart: `${date}T${hhmm}:00`, startsAt: `${date}T${hhmm}:00Z`, endsAt: `${date}T${hhmm}:00Z` })

function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((r) => (resolve = r))
  return { promise, resolve }
}

async function openAgenda(routes: Routes, role = 'ATTENDANT') {
  const { calls } = mockApi({
    'POST /api/auth/login': () => token(),
    'GET /api/me': () => json(200, { ...USER, role, name: 'Fulana Silva' }),
    'GET /api/agenda/workplace': () => json(200, WORKPLACE),
    ...routes,
  })
  const view = renderWithHarness()
  await act(async () => view.api().login('x@exemplo.test', PASSWORD))
  await screen.findByRole('heading', { name: 'Olá, Fulana Silva' })
  const user = userEvent.setup()
  await user.click(within(screen.getByRole('navigation')).getByRole('link', { name: 'Agenda' }))
  await screen.findByRole('heading', { name: 'Agenda' })
  return { calls, user }
}

const gets = (calls: Call[], path: string) => calls.filter((c) => c.method === 'GET' && c.path === path)
const posts = (calls: Call[], suffix: string) => calls.filter((c) => c.method === 'POST' && c.path.endsWith(suffix))

async function openBox(user: ReturnType<typeof userEvent.setup>) {
  await openMore(user)
  await user.click(await screen.findByRole('button', { name: 'Remarcar' }))
  return screen.findByRole('region', { name: 'Remarcar agendamento' })
}

describe('agenda da equipe: remarcar', () => {
  it('só agendamento agendado tem Remarcar; a caixa pede os horários do prestador ignorando o próprio agendamento e explica que a equipe não tem limite', async () => {
    const { calls, user } = await openAgenda({
      [DAY]: () => json(200, [item({ id: 'a-1' }), item({ id: 'a-2', status: 'CHECKED_IN' }), item({ id: 'a-3', status: 'COMPLETED' })]),
      [`GET ${slotsPath(today)}`]: () => json(200, [slot(today, '16:00')]),
    })
    await screen.findAllByRole('listitem')
    await openMore(user)
    expect(screen.getAllByRole('button', { name: 'Remarcar' })).toHaveLength(1)
    const box = await openBox(user)
    await within(box).findByRole('button', { name: '16:00' })
    expect(gets(calls, slotsPath(today))).toHaveLength(1)
    expect(within(box).getByText(/sem prazo e sem limite/)).toBeInTheDocument()
  })

  it('no mesmo dia: envia o horário local, avisa, refaz a lista e leva o foco para o aviso', async () => {
    let dayCalls = 0
    const moved = item({ startsAt: `${today}T19:00:00Z`, endsAt: `${today}T20:00:00Z` })
    const { calls, user } = await openAgenda({
      [DAY]: () => json(200, ++dayCalls === 1 ? [item()] : [moved]),
      [`GET ${slotsPath(today)}`]: () => json(200, [slot(today, '16:00')]),
      'POST /api/appointments/a-1/reschedule': () => json(200, moved),
    })
    const box = await openBox(user)
    await user.click(await within(box).findByRole('button', { name: '16:00' }))
    await user.click(within(box).getByRole('button', { name: 'Confirmar remarcação' }))

    const notice = await screen.findByText(/Agendamento de Maria Souza remarcado para .*às 16:00\./)
    expect(posts(calls, '/reschedule')[0].body).toEqual({ localStart: `${today}T16:00:00` })
    await waitFor(() => expect(gets(calls, `/api/agenda?date=${today}`)).toHaveLength(2))
    expect(screen.queryByRole('region', { name: 'Remarcar agendamento' })).not.toBeInTheDocument()
    expect(notice).toHaveFocus()
    expect(await screen.findByText('16:00–17:00')).toBeInTheDocument()
  })

  it('para OUTRO dia: a linha sai da lista na hora, mesmo que a lista seguinte ainda a mostre', async () => {
    const { user } = await openAgenda({
      [DAY]: () => json(200, [item()]), // a lista refeita ainda traz o agendamento (resposta de antes da mudança)
      [`GET ${slotsPath(today)}`]: () => json(200, []),
      [`GET ${slotsPath(tomorrow)}`]: () => json(200, [slot(tomorrow, '09:00')]),
      'POST /api/appointments/a-1/reschedule': () => json(200, item({ startsAt: `${tomorrow}T12:00:00Z`, endsAt: `${tomorrow}T13:00:00Z` })),
    })
    const box = await openBox(user)
    await user.click(within(box).getByRole('button', { name: 'Próximo dia' }))
    await user.click(await within(box).findByRole('button', { name: '09:00' }))
    await user.click(within(box).getByRole('button', { name: 'Confirmar remarcação' }))

    await screen.findByText(/Agendamento de Maria Souza remarcado para/)
    await waitFor(() => expect(screen.queryByRole('listitem')).not.toBeInTheDocument())
    expect(screen.getByText(/Agendamento de Maria Souza remarcado para/)).toHaveFocus()
  })

  it('horário tomado (409) mantém a caixa e refaz os horários; agendamento que já começou (409) fecha a caixa, avisa e refaz a lista', async () => {
    let slotCalls = 0
    let reason = 'SLOT_TAKEN'
    const { calls, user } = await openAgenda({
      [DAY]: () => json(200, [item()]),
      [`GET ${slotsPath(today)}`]: () => json(200, [slot(today, ++slotCalls === 1 ? '16:00' : '17:00')]),
      'POST /api/appointments/a-1/reschedule': () => problem(409, 'x', { reason }),
    })
    const box = await openBox(user)
    await user.click(await within(box).findByRole('button', { name: '16:00' }))
    await user.click(within(box).getByRole('button', { name: 'Confirmar remarcação' }))
    expect(await screen.findByText('Esse horário acabou de ser ocupado por outra pessoa. Escolha outro.')).toBeInTheDocument()
    expect(await within(screen.getByRole('region', { name: 'Remarcar agendamento' })).findByRole('button', { name: '17:00' })).toBeInTheDocument()

    reason = 'NOT_RESCHEDULABLE'
    await user.click(within(screen.getByRole('region', { name: 'Remarcar agendamento' })).getByRole('button', { name: '17:00' }))
    await user.click(screen.getByRole('button', { name: 'Confirmar remarcação' }))
    expect(await screen.findByText('Esse agendamento já começou e não pode mais ser remarcado.')).toBeInTheDocument()
    expect(screen.queryByRole('region', { name: 'Remarcar agendamento' })).not.toBeInTheDocument()
    await waitFor(() => expect(gets(calls, `/api/agenda?date=${today}`)).toHaveLength(2))
  })

  it('dois envios no mesmo instante remarcam uma vez só', async () => {
    const pending = deferred<Response>()
    const { calls, user } = await openAgenda({
      [DAY]: () => json(200, [item()]),
      [`GET ${slotsPath(today)}`]: () => json(200, [slot(today, '16:00')]),
      'POST /api/appointments/a-1/reschedule': () => pending.promise,
    })
    const box = await openBox(user)
    await user.click(await within(box).findByRole('button', { name: '16:00' }))
    const confirm = within(box).getByRole('button', { name: 'Confirmar remarcação' })
    act(() => {
      fireEvent.click(confirm)
      fireEvent.click(confirm)
    })
    expect(await within(box).findByRole('button', { name: 'Remarcando…' })).toBeDisabled()
    expect(posts(calls, '/reschedule')).toHaveLength(1)
    await act(async () => pending.resolve(json(200, item())))
  })
})

describe('agenda da equipe: histórico', () => {
  it('a equipe vê o nome de quem agiu', async () => {
    const { user } = await openAgenda({
      [DAY]: () => json(200, [item()]),
      'GET /api/appointments/a-1/events': () =>
        json(200, [
          { type: 'SCHEDULED', at: `${today}T10:00:00Z`, by: 'CUSTOMER', actorName: 'Maria Souza' },
          { type: 'RESCHEDULED', at: `${today}T11:00:00Z`, by: 'STAFF', actorName: 'Beatriz da Equipe', previousStartsAt: `${today}T14:00:00Z`, newStartsAt: `${today}T15:00:00Z` },
        ]),
    })
    await user.click(await screen.findByRole('button', { name: 'Histórico' }))
    const group = await screen.findByRole('group', { name: 'Histórico do agendamento' })
    const lines = within(group).getAllByRole('listitem')
    expect(lines[0]).toHaveTextContent('Agendado por Maria Souza')
    expect(lines[1]).toHaveTextContent('Remarcado por Beatriz da Equipe: de')
  })
})
