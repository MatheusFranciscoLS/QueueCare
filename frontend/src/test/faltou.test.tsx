import { act, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { todayIn } from '../api/dates'
import { json, mockApi, PASSWORD, problem, renderWithHarness, token, USER, type Call, openMore } from './helpers'

vi.mock('../pages/customer/CustomerPanel', () => ({ CustomerPanel: () => null }))
vi.mock('../pages/provider/SetupPanel', () => ({ SetupPanel: () => null }))
vi.mock('../pages/TodaySummary', () => ({ TodaySummary: () => null }))
afterEach(() => vi.unstubAllGlobals())

type Routes = Record<string, (call: Call) => Response | Promise<Response>>

const ZONE = 'America/Sao_Paulo'
const today = todayIn(ZONE)
const hoursFromNow = (h: number) => new Date(Date.now() + h * 3_600_000).toISOString()

const item = (id: string, startsAt: string, extra: object = {}) => ({
  id,
  startsAt,
  endsAt: new Date(Date.parse(startsAt) + 3_600_000).toISOString(),
  status: 'SCHEDULED',
  serviceId: 's-1',
  serviceName: 'Banho',
  customerId: 'c-1',
  customerName: 'Joana Lima',
  customerNoShows: 0,
  pet: { name: 'Rex', species: 'DOG', breed: null, size: 'MEDIUM', notes: null },
  ...extra,
})

async function openAgenda(routes: Routes) {
  const { calls } = mockApi({
    'POST /api/auth/login': () => token(),
    'GET /api/me': () => json(200, { ...USER, role: 'ATTENDANT', name: 'Maria Souza' }),
    'GET /api/agenda/workplace': () => json(200, { providerId: 'p-1', businessName: 'Estúdio Pelo e Ponto', timeZone: ZONE }),
    ...routes,
  })
  const view = renderWithHarness()
  await act(async () => view.api().login('x@exemplo.test', PASSWORD))
  await screen.findByRole('heading', { name: 'Olá, Maria Souza' })
  const user = userEvent.setup()
  await user.click(within(screen.getByRole('navigation')).getByRole('link', { name: 'Agenda' }))
  return { calls, user }
}

const AGENDA = `GET /api/agenda?date=${today}`

describe('agenda: faltou', () => {
  it('só oferece "Faltou" quando o horário marcado já chegou', async () => {
    const { user } = await openAgenda({ [AGENDA]: () => json(200, [item('a-1', hoursFromNow(-2)), item('a-2', hoursFromNow(3), { customerName: 'Pedro Alves' })]) })
    const rows = await screen.findAllByRole('listitem')
    await openMore(user, within(rows[0]))
    expect(within(rows[0]).getByRole('button', { name: 'Faltou' })).toBeInTheDocument()
    expect(within(rows[1]).queryByRole('button', { name: 'Faltou' })).not.toBeInTheDocument()
  })

  it('pede confirmação (estado final), "Voltar" não envia nada e a confirmação envia e avisa', async () => {
    let missed = false
    const { calls, user } = await openAgenda({
      [AGENDA]: () => json(200, [item('a-1', hoursFromNow(-2), missed ? { status: 'NO_SHOW', customerNoShows: 1 } : {})]),
      'POST /api/appointments/a-1/no-show': () => {
        missed = true
        return json(200, { id: 'a-1', status: 'NO_SHOW' })
      },
    })
    await openMore(user)
    await user.click(await screen.findByRole('button', { name: 'Faltou' }))
    expect(screen.getByText('Registrar que Joana Lima faltou? Não dá para desfazer.')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Voltar' })).toHaveFocus() // a opção segura

    await user.click(screen.getByRole('button', { name: 'Voltar' }))
    expect(calls.some((c) => c.path.endsWith('/no-show'))).toBe(false)
    await waitFor(() => expect(screen.getByRole('button', { name: 'Faltou' })).toHaveFocus()) // o foco volta para quem abriu

    await openMore(user)
    await user.click(screen.getByRole('button', { name: 'Faltou' }))
    await user.click(screen.getByRole('button', { name: 'Confirmar falta' }))

    expect(await screen.findByText('Falta de Joana Lima registrada.')).toBeInTheDocument()
    expect(calls.filter((c) => c.method === 'POST' && c.path === '/api/appointments/a-1/no-show')).toHaveLength(1)
    const row = (await screen.findAllByRole('listitem'))[0]
    await waitFor(() => expect(within(row).getByText('Faltou', { selector: '.badge' })).toBeInTheDocument())
    expect(within(row).queryByRole('button', { name: 'Faltou' })).not.toBeInTheDocument() // estado final: sem mais ações
    expect(within(row).queryByRole('button', { name: 'Remarcar' })).not.toBeInTheDocument()
  })

  it('recusa TOO_EARLY mostra o motivo e não muda a linha', async () => {
    const { user } = await openAgenda({
      [AGENDA]: () => json(200, [item('a-1', hoursFromNow(-1))]),
      'POST /api/appointments/a-1/no-show': () => problem(422, 'operação recusada', { reason: 'TOO_EARLY' }),
    })
    await openMore(user)
    await user.click(await screen.findByRole('button', { name: 'Faltou' }))
    await user.click(screen.getByRole('button', { name: 'Confirmar falta' }))
    expect(await screen.findByText('Só dá para registrar a falta a partir do horário do agendamento.')).toBeInTheDocument()
    await openMore(user)
    expect(screen.getByRole('button', { name: 'Faltou' })).toBeInTheDocument()
  })

  it('mostra quantas vezes o tutor faltou neste estúdio, só quando há falta', async () => {
    await openAgenda({
      [AGENDA]: () =>
        json(200, [
          item('a-1', hoursFromNow(1), { customerNoShows: 2 }),
          item('a-2', hoursFromNow(2), { customerName: 'Pedro Alves', customerNoShows: 1 }),
          item('a-3', hoursFromNow(3), { customerName: 'Ana Costa', customerNoShows: 0 }),
        ]),
    })
    const rows = await screen.findAllByRole('listitem')
    expect(within(rows[0]).getByText('2 faltas')).toBeInTheDocument()
    expect(within(rows[1]).getByText('1 falta')).toBeInTheDocument()
    expect(within(rows[2]).queryByText(/falta/)).not.toBeInTheDocument()
  })

  it('o resumo do dia conta quem faltou', async () => {
    await openAgenda({
      [AGENDA]: () => json(200, [item('a-1', hoursFromNow(-3), { status: 'NO_SHOW' }), item('a-2', hoursFromNow(-2), { status: 'NO_SHOW', customerName: 'Pedro Alves' }), item('a-3', hoursFromNow(2))]),
    })
    expect(await screen.findByText(/2 faltaram/)).toBeInTheDocument()
  })
})

describe('meus agendamentos: faltou', () => {
  it('o tutor vê "Faltou" e nenhum botão de mudança', async () => {
    mockApi({
      'POST /api/auth/login': () => token(),
      'GET /api/me': () => json(200, { ...USER, role: 'CUSTOMER', name: 'Maria Souza' }),
      'GET /api/appointments/mine?page=0&size=50': () =>
        json(200, [{ id: 'a-1', providerId: 'p-1', serviceId: 's-1', startsAt: '2026-11-01T13:00:00Z', endsAt: '2026-11-01T14:00:00Z', status: 'NO_SHOW', businessName: 'Estúdio', timeZone: ZONE, serviceName: 'Banho' }]),
    })
    const view = renderWithHarness()
    await act(async () => view.api().login('x@exemplo.test', PASSWORD))
    await screen.findByRole('heading', { name: 'Olá, Maria Souza' })
    await userEvent.setup().click(within(screen.getByRole('navigation', { name: 'Menu do cliente' })).getByRole('link', { name: 'Meus agendamentos' }))
    const row = (await screen.findAllByRole('listitem'))[0]
    expect(within(row).getByText('Faltou')).toBeInTheDocument()
    expect(within(row).queryByRole('button', { name: /Cancelar|Remarcar/ })).not.toBeInTheDocument()
  })
})
