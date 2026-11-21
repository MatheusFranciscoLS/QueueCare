import { act, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { todayIn } from '../api/dates'
import { json, mockApi, PASSWORD, problem, renderWithHarness, token, USER, type Call } from './helpers'

vi.mock('../pages/customer/CustomerPanel', () => ({ CustomerPanel: () => null }))
vi.mock('../pages/provider/SetupPanel', () => ({ SetupPanel: () => null }))
vi.mock('../pages/TodaySummary', () => ({ TodaySummary: () => null }))
afterEach(() => vi.unstubAllGlobals())

type Routes = Record<string, (call: Call) => Response | Promise<Response>>

const ZONE = 'America/Sao_Paulo'
const today = todayIn(ZONE)
const hoursFromNow = (h: number) => new Date(Date.now() + h * 3_600_000).toISOString()

const item = (id: string, extra: object = {}) => ({
  id,
  startsAt: hoursFromNow(3),
  endsAt: hoursFromNow(4),
  status: 'SCHEDULED',
  serviceId: 's-1',
  serviceName: 'Banho',
  customerId: 'c-1',
  customerName: 'Joana Lima',
  customerPhone: '5511912345678',
  customerNoShows: 0,
  pet: { name: 'Mel', species: 'DOG', breed: null, size: 'MEDIUM', notes: null },
  ...extra,
})

async function open(menuItem: 'Agenda' | 'Fila', routes: Routes) {
  return (await openWithCalls(menuItem, routes)).user
}

async function openWithCalls(menuItem: 'Agenda' | 'Fila', routes: Routes) {
  const { calls } = mockApi({
    'POST /api/auth/login': () => token(),
    'GET /api/me': () => json(200, { ...USER, role: 'ATTENDANT', name: 'Maria Souza' }),
    'GET /api/agenda/workplace': () => json(200, { providerId: 'p-1', businessName: 'Estúdio', timeZone: ZONE, cancellationDeadlineMinutes: 720 }),
    ...routes,
  })
  const view = renderWithHarness()
  await act(async () => view.api().login('x@exemplo.test', PASSWORD))
  await screen.findByRole('heading', { name: 'Olá, Maria Souza' })
  const user = userEvent.setup()
  await user.click(within(screen.getByRole('navigation')).getByRole('link', { name: menuItem }))
  return { user, calls }
}

describe('Agenda: uma ação principal e "Mais ações"', () => {
  const AGENDA = 'GET /api/agenda?date=' + today

  it('agendado de hoje: mostra "Chegou" e "Lembrar"; remarcar, cancelar e faltou ficam atrás de "Mais ações"', async () => {
    const user = await open('Agenda', { [AGENDA]: () => json(200, [item('a-1', { startsAt: hoursFromNow(-1) })]) })
    expect(await screen.findByRole('button', { name: 'Chegou' })).toBeInTheDocument()
    const toggle = screen.getByRole('button', { name: 'Mais ações de Joana Lima' })
    expect(toggle).toHaveAttribute('aria-expanded', 'false')
    expect(screen.queryByRole('button', { name: 'Remarcar' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Cancelar agendamento' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Faltou' })).not.toBeInTheDocument()

    await user.click(toggle)

    expect(toggle).toHaveAttribute('aria-expanded', 'true')
    const group = screen.getByRole('group', { name: 'Mais ações de Joana Lima' })
    expect(toggle).toHaveAttribute('aria-controls', group.id)
    expect(within(group).getByRole('button', { name: 'Remarcar' })).toBeInTheDocument()
    expect(within(group).getByRole('button', { name: 'Cancelar agendamento' })).toBeInTheDocument()
    expect(within(group).getByRole('button', { name: 'Faltou' })).toBeInTheDocument() // o horário já chegou

    await user.click(toggle) // fecha de novo
    expect(screen.queryByRole('group', { name: 'Mais ações de Joana Lima' })).not.toBeInTheDocument()
  })

  it('o botão "Sinal recebido" só aparece, dentro de "Mais ações", quando o sinal está pendente', async () => {
    const user = await open('Agenda', { [AGENDA]: () => json(200, [item('a-1', { depositCents: 3000, depositStatus: 'REQUESTED' }), item('a-2', { customerName: 'Pedro Alves' })]) })
    const rows = await screen.findAllByRole('listitem')
    await user.click(within(rows[0]).getByRole('button', { name: 'Mais ações de Joana Lima' }))
    expect(within(rows[0]).getByRole('button', { name: 'Sinal recebido de Joana Lima' })).toBeInTheDocument()
    await user.click(within(rows[1]).getByRole('button', { name: 'Mais ações de Pedro Alves' }))
    expect(within(rows[1]).queryByRole('button', { name: /Sinal recebido de/ })).not.toBeInTheDocument()
  })

  it('quem já chegou tem "Concluir" como principal e SÓ "Desfazer" em "Mais ações" (nada de remarcar, cancelar ou faltou)', async () => {
    const user = await open('Agenda', { [AGENDA]: () => json(200, [item('a-1', { status: 'IN_PROGRESS' })]) })
    expect(await screen.findByRole('button', { name: 'Concluir atendimento' })).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Mais ações de Joana Lima' }))
    const group = screen.getByRole('group', { name: 'Mais ações de Joana Lima' })
    expect(within(group).getAllByRole('button').map((b) => b.textContent)).toEqual(['Desfazer início'])
  })

  it('desfazer a chegada chama a API, avisa o que foi desfeito e relê a agenda', async () => {
    let status = 'CHECKED_IN'
    const { calls, user } = await openWithCalls('Agenda', {
      [AGENDA]: () => json(200, [item('a-1', { status })]),
      'POST /api/appointments/a-1/undo': () => {
        status = 'SCHEDULED'
        return json(200, { id: 'a-1', status: 'SCHEDULED' })
      },
    })
    await user.click(await screen.findByRole('button', { name: 'Mais ações de Joana Lima' }))
    await user.click(screen.getByRole('button', { name: 'Desfazer chegada' }))

    expect(await screen.findByText('Desfeito: chegada de Joana Lima.')).toBeInTheDocument()
    expect(calls.some((c) => c.method === 'POST' && c.path === '/api/appointments/a-1/undo')).toBe(true)
    expect(await screen.findByRole('button', { name: 'Chegou' })).toBeInTheDocument() // voltou a "Agendado": a principal é "Chegou" de novo
  })

  it('o histórico conta a correção com a etapa e quem desfez', async () => {
    const user = await open('Agenda', {
      [AGENDA]: () => json(200, [item('a-1')]),
      'GET /api/appointments/a-1/events': () =>
        json(200, [{ type: 'UNDONE', at: '2026-11-21T15:00:00Z', by: 'STAFF', actorName: 'Maria Souza', undone: 'CHECKED_IN' }]),
    })
    await user.click(await screen.findByRole('button', { name: 'Histórico' }))
    expect(await screen.findByText(/Etapa desfeita \(chegada\) por Maria Souza/)).toBeInTheDocument()
  })

  it('faltas, sinal e lembrete ficam numa linha de indicadores discreta, separada do estado do atendimento', async () => {
    await open('Agenda', {
      [AGENDA]: () => json(200, [item('a-1', { customerNoShows: 2, depositCents: 3000, depositStatus: 'REQUESTED', remindedAt: '2026-11-20T20:00:00Z' })]),
    })
    const row = (await screen.findAllByRole('listitem'))[0]
    const flags = row.querySelector('.appt-flags') as HTMLElement
    expect(flags).not.toBeNull()
    expect(flags).toHaveTextContent('2 faltas')
    expect(flags).toHaveTextContent('Sinal pendente · R$ 30,00')
    expect(flags).toHaveTextContent('Lembrete enviado em')
    const status = row.querySelector('.badge') as HTMLElement
    expect(status).toHaveTextContent('Agendado')
    expect(flags.contains(status)).toBe(false) // o estado é um só e fica fora dos indicadores
  })

  it('atendimento concluído não repete a ficha do pet; o agendado de hoje mostra', async () => {
    const ficha = { coat: 'SHORT', weightKg: 4.2, vaccinated: true, temperament: 'CALM', healthNotes: 'Pele sensível', notes: 'Tem medo de secador' }
    await open('Agenda', {
      [AGENDA]: () =>
        json(200, [
          item('a-1', { status: 'COMPLETED', customerName: 'Concluída Silva', pet: { name: 'Mel', species: 'CAT', breed: null, size: 'SMALL', ...ficha } }),
          item('a-2', { customerName: 'Agendada Souza', pet: { name: 'Thor', species: 'DOG', breed: null, size: 'LARGE', ...ficha } }),
        ]),
    })
    const rows = await screen.findAllByRole('listitem')
    expect(within(rows[0]).getByText('Mel')).toBeInTheDocument() // o pet continua identificado
    expect(within(rows[0]).queryByText(/Pele sensível/)).not.toBeInTheDocument()
    expect(within(rows[0]).queryByText(/medo de secador/)).not.toBeInTheDocument()
    expect(within(rows[1]).getByText(/Pele sensível/)).toBeInTheDocument()
    expect(within(rows[1]).getByText(/medo de secador/)).toBeInTheDocument()
  })

  it('sem indicadores, a linha de indicadores nem existe', async () => {
    await open('Agenda', { [AGENDA]: () => json(200, [item('a-1')]) })
    const row = (await screen.findAllByRole('listitem'))[0]
    expect(row.querySelector('.appt-flags')).toBeNull()
  })
})

describe('Fila: uma ação principal por estado', () => {
  const queueItem = (id: string, status: string, extra: object = {}) => ({
    position: 1,
    id,
    status,
    startsAt: hoursFromNow(-1),
    endsAt: hoursFromNow(0),
    serviceId: 's-1',
    serviceName: 'Banho',
    customerId: 'c-1',
    customerName: 'Joana Lima',
    customerPhone: '5511912345678',
    checkedInAt: hoursFromNow(-0.5),
    pet: { name: 'Mel', species: 'DOG', breed: null, size: 'MEDIUM', notes: null },
    ...extra,
  })

  it('esperando: "Começar atendimento" é a principal; pular etapas fica em "Mais ações"', async () => {
    const user = await open('Fila', { 'GET /api/agenda/queue': () => json(200, [queueItem('a-1', 'CHECKED_IN')]) })
    expect(await screen.findByRole('button', { name: 'Começar atendimento' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Pronto para retirada' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Concluir atendimento' })).not.toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Mais ações de Joana Lima' }))
    expect(screen.getByRole('button', { name: 'Pronto para retirada' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Concluir atendimento' })).toBeInTheDocument()
  })

  it('em atendimento: "Pronto para retirada" é a principal; concluir direto fica em "Mais ações"', async () => {
    const user = await open('Fila', { 'GET /api/agenda/queue': () => json(200, [queueItem('a-1', 'IN_PROGRESS')]) })
    expect(await screen.findByRole('button', { name: 'Pronto para retirada' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Começar atendimento' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Concluir atendimento' })).not.toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Mais ações de Joana Lima' }))
    expect(screen.getByRole('button', { name: 'Concluir atendimento' })).toBeInTheDocument()
  })

  it('pronto para retirada: "Pet retirado" é a principal, o WhatsApp é o secundário e "Mais ações" só desfaz', async () => {
    const user = await open('Fila', { 'GET /api/agenda/queue': () => json(200, [queueItem('a-1', 'READY')]) })
    expect(await screen.findByRole('button', { name: 'Pet retirado (concluir)' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /Avisar no WhatsApp/ })).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Mais ações de Joana Lima' }))
    const group = screen.getByRole('group', { name: 'Mais ações de Joana Lima' })
    expect(within(group).getAllByRole('button').map((b) => b.textContent)).toEqual(['Desfazer pronto'])
  })

  it('desfazer a chegada na fila tira a pessoa da fila e avisa', async () => {
    let queue = [queueItem('a-1', 'CHECKED_IN')]
    const { calls, user } = await openWithCalls('Fila', {
      'GET /api/agenda/queue': () => json(200, queue),
      'POST /api/appointments/a-1/undo': () => {
        queue = []
        return json(200, { id: 'a-1', status: 'SCHEDULED' })
      },
    })
    await user.click(await screen.findByRole('button', { name: 'Mais ações de Joana Lima' }))
    await user.click(screen.getByRole('button', { name: 'Desfazer chegada' }))

    expect(await screen.findByText('Desfeito: chegada de Mel (Joana Lima).')).toBeInTheDocument()
    expect(calls.some((c) => c.method === 'POST' && c.path === '/api/appointments/a-1/undo')).toBe(true)
    await waitFor(() => expect(screen.queryByRole('button', { name: 'Começar atendimento' })).not.toBeInTheDocument())
  })

  it('o servidor já ter mudado (409) mostra o erro e refaz a fila', async () => {
    const { user } = await openWithCalls('Fila', {
      'GET /api/agenda/queue': () => json(200, [queueItem('a-1', 'IN_PROGRESS')]),
      'POST /api/appointments/a-1/undo': () => problem(409, 'estado', { reason: 'INVALID_STATE' }),
    })
    await user.click(await screen.findByRole('button', { name: 'Mais ações de Joana Lima' }))
    await user.click(screen.getByRole('button', { name: 'Desfazer início' }))
    expect(await screen.findByRole('alert')).toBeInTheDocument()
  })
})
