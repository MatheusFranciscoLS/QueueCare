import { act, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { todayIn } from '../api/dates'
import { calledStamp, waitlistCallLink, waitlistCallMessage, type WaitlistStaff } from '../api/waitlist'
import { json, mockApi, PASSWORD, problem, renderWithHarness, token, USER, type Call } from './helpers'

vi.mock('../pages/customer/CustomerPanel', () => ({ CustomerPanel: () => null }))
vi.mock('../pages/provider/SetupPanel', () => ({ SetupPanel: () => null }))
vi.mock('../pages/TodaySummary', () => ({ TodaySummary: () => null }))
afterEach(() => vi.unstubAllGlobals())

type Routes = Record<string, (call: Call) => Response | Promise<Response>>

const ZONE = 'America/Sao_Paulo'
const today = todayIn(ZONE)

const staffEntry = (extra: Partial<WaitlistStaff> = {}): WaitlistStaff => ({
  id: 'w-1',
  customerId: 'c-1',
  customerName: 'Joana Lima',
  customerPhone: '5511912345678',
  petName: 'Mel',
  petSpecies: 'DOG',
  serviceName: 'Banho',
  durationMinutes: 90,
  createdAt: '2026-11-20T12:00:00Z',
  calledAt: null,
  ...extra,
})

describe('lista de espera (funções)', () => {
  it('a mensagem da equipe avisa que vagou, com serviço, pet e duração, sem artigos que errariam o gênero', () => {
    const text = waitlistCallMessage(staffEntry(), 'Estúdio Pelo e Ponto', '2026-12-10')
    expect(text).toBe('Olá, Joana! Aqui é Estúdio Pelo e Ponto. Vagou um horário em quinta-feira, 10 de dezembro de 2026: Banho de Mel, cerca de 90 min. Você ainda tem interesse?')
    expect(text).not.toMatch(/\b(do|da) (Banho|Mel)/)
  })

  it('sem telefone não há link', () => {
    expect(waitlistCallLink(staffEntry({ customerPhone: null }), 'Estúdio', '2026-12-10')).toBeNull()
    expect(waitlistCallLink(staffEntry(), 'Estúdio', '2026-12-10')).toMatch(/^https:\/\/wa\.me\/5511912345678\?text=/)
  })

  it('"chamado em" usa o fuso do negócio', () => {
    expect(calledStamp('2026-11-20T20:02:00Z', ZONE)).toBe('20/11, 17:02')
  })
})

async function loginAs(role: string, routes: Routes) {
  const { calls } = mockApi({
    'POST /api/auth/login': () => token(),
    'GET /api/me': () => json(200, { ...USER, role, name: 'Maria Souza' }),
    ...routes,
  })
  const view = renderWithHarness()
  await act(async () => view.api().login('x@exemplo.test', PASSWORD))
  await screen.findByRole('heading', { name: 'Olá, Maria Souza' })
  return { calls, user: userEvent.setup() }
}

describe('agendar: dia sem horário oferece a lista de espera', () => {
  const SLOTS_TODAY = 'GET /api/providers/p-1/slots?serviceId=s-1&date=' + today + '&petId=pet-1'
  const open = async (routes: Routes) => {
    const ctx = await loginAs('CUSTOMER', {
      'GET /api/catalog/providers?page=0&size=50': () => json(200, [{ id: 'p-1', businessName: 'Estúdio Pelo e Ponto', timeZone: ZONE, cancellationDeadlineMinutes: 720, phone: null }]),
      'GET /api/catalog/providers/p-1/services': () => json(200, [{ id: 's-1', name: 'Banho', durationMinutes: 60 }]),
      'GET /api/customer/pets': () => json(200, [{ id: 'pet-1', name: 'Rex', species: 'DOG', breed: null, size: 'SMALL', notes: null, active: true }]),
      ...routes,
    })
    await ctx.user.click(within(screen.getByRole('navigation', { name: 'Menu do cliente' })).getByRole('link', { name: 'Agendar' }))
    await ctx.user.click(await screen.findByRole('radio', { name: /Banho/ }))
    return ctx
  }
  const NO_DAYS: Routes = {}
  for (let i = 0; i < 14; i++) {
    const d = new Date(Date.parse(today + 'T12:00:00Z') + i * 86_400_000).toISOString().slice(0, 10)
    NO_DAYS['GET /api/providers/p-1/slots?serviceId=s-1&date=' + d + '&petId=pet-1'] = () => json(200, [])
  }

  it('entra na lista com o estúdio, serviço, pet e dia, e confirma sem prometer reserva', async () => {
    const { calls, user } = await open({ ...NO_DAYS, 'POST /api/waitlist': () => json(201, { id: 'w-1' }) })
    await user.click(await screen.findByRole('button', { name: 'Avise-me se vagar um horário neste dia' }))

    expect(await screen.findByText(/Você está na lista de espera de .*Se vagar um horário, o estúdio chama você pelo WhatsApp\./)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Ver minha lista de espera' })).toHaveAttribute('href', '/meus-agendamentos')
    expect(calls.find((c) => c.method === 'POST' && c.path === '/api/waitlist')?.body).toEqual({ providerId: 'p-1', serviceId: 's-1', petId: 'pet-1', date: today })
  })

  it('a oferta só aparece quando o dia não tem horário', async () => {
    await open({ [SLOTS_TODAY]: () => json(200, [{ localStart: today + 'T09:00:00', startsAt: today + 'T12:00:00Z', endsAt: today + 'T13:00:00Z' }]) })
    expect(await screen.findByRole('button', { name: '09:00' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Avise-me se vagar um horário neste dia' })).not.toBeInTheDocument()
  })

  it('recusa da API aparece no lugar e o botão continua disponível', async () => {
    const { user } = await open({ ...NO_DAYS, 'POST /api/waitlist': () => problem(422, 'pedido recusado', { reason: 'WAITLIST_LIMIT' }) })
    await user.click(await screen.findByRole('button', { name: 'Avise-me se vagar um horário neste dia' }))
    expect(await screen.findByText('Você já está em muitas listas de espera. Saia de uma para entrar em outra.')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Avise-me se vagar um horário neste dia' })).toBeEnabled()
  })
})

describe('meus agendamentos: lista de espera do tutor', () => {
  const open = async (waitlist: object[], extra: Routes = {}) => {
    const ctx = await loginAs('CUSTOMER', { 'GET /api/appointments/mine?page=0&size=50': () => json(200, []), 'GET /api/waitlist/mine': () => json(200, waitlist), ...extra })
    await ctx.user.click(within(screen.getByRole('navigation', { name: 'Menu do cliente' })).getByRole('link', { name: 'Meus agendamentos' }))
    return ctx
  }

  it('mostra o dia, o serviço, o estúdio e o pet, e sair da lista chama a API e atualiza', async () => {
    let list = [{ id: 'w-1', providerId: 'p-1', businessName: 'Estúdio Pelo e Ponto', serviceName: 'Banho', petName: 'Mel', date: '2099-12-10' }]
    const { calls, user } = await open(list, {
      'GET /api/waitlist/mine': () => json(200, list),
      'DELETE /api/waitlist/w-1': () => {
        list = []
        return new Response(null, { status: 204 })
      },
    })
    const section = await screen.findByRole('region', { name: 'Lista de espera' })
    expect(within(section).getByText(/Banho em Estúdio Pelo e Ponto/)).toBeInTheDocument()
    expect(within(section).getByText(/Mel/)).toBeInTheDocument()
    expect(within(section).getByText(/Isso não reserva o horário/)).toBeInTheDocument()

    await user.click(within(section).getByRole('button', { name: 'Sair da lista' }))

    await waitFor(() => expect(screen.queryByRole('region', { name: 'Lista de espera' })).not.toBeInTheDocument())
    expect(calls.filter((c) => c.method === 'DELETE' && c.path === '/api/waitlist/w-1')).toHaveLength(1)
  })

  it('sem entradas, a seção não aparece', async () => {
    await open([])
    await screen.findByText(/Você ainda não tem agendamentos/)
    expect(screen.queryByRole('region', { name: 'Lista de espera' })).not.toBeInTheDocument()
  })
})

describe('agenda: lista de espera do dia (equipe)', () => {
  const AGENDA = 'GET /api/agenda?date=' + today
  const open = async (entries: WaitlistStaff[], extra: Routes = {}) => {
    mockApi({
      'POST /api/auth/login': () => token(),
      'GET /api/me': () => json(200, { ...USER, role: 'ATTENDANT', name: 'Maria Souza' }),
      'GET /api/agenda/workplace': () => json(200, { providerId: 'p-1', businessName: 'Estúdio Pelo e Ponto', timeZone: ZONE, cancellationDeadlineMinutes: 720 }),
      [AGENDA]: () => json(200, []),
      ['GET /api/agenda/waitlist?date=' + today]: () => json(200, entries),
      ...extra,
    })
    const view = renderWithHarness()
    await act(async () => view.api().login('x@exemplo.test', PASSWORD))
    await screen.findByRole('heading', { name: 'Olá, Maria Souza' })
    const user = userEvent.setup()
    await user.click(within(screen.getByRole('navigation')).getByRole('link', { name: 'Agenda' }))
    return user
  }

  it('lista por ordem de entrada, com a duração prevista para o pet e o WhatsApp com a mensagem pronta', async () => {
    await open([staffEntry(), staffEntry({ id: 'w-2', customerName: 'Pedro Alves', petName: 'Thor', durationMinutes: 120, customerPhone: null })])
    const section = await screen.findByRole('region', { name: 'Lista de espera' })
    expect(within(section).getByRole('heading', { name: 'Lista de espera (2)' })).toBeInTheDocument()
    const rows = within(section).getAllByRole('listitem')
    expect(rows[0]).toHaveTextContent('Mel · Joana Lima · Banho, cerca de 90 min')
    expect(rows[1]).toHaveTextContent('Thor · Pedro Alves · Banho, cerca de 120 min')
    const link = within(rows[0]).getByRole('link', { name: 'Chamar Joana Lima pelo WhatsApp' })
    expect(link.getAttribute('href')).toMatch(/^https:\/\/wa\.me\/5511912345678\?text=/)
    expect(within(rows[1]).queryByRole('link', { name: /Chamar/ })).not.toBeInTheDocument() // sem telefone: sem link
  })

  it('chamar guarda quando e mostra "Chamado em"; tirar da lista remove', async () => {
    let entries = [staffEntry()]
    const calls: string[] = []
    const user = await open(entries, {
      ['GET /api/agenda/waitlist?date=' + today]: () => json(200, entries),
      'POST /api/waitlist/w-1/called': () => {
        calls.push('called')
        entries = [staffEntry({ calledAt: '2026-11-20T20:02:00Z' })]
        return json(200, { id: 'w-1' })
      },
      'DELETE /api/waitlist/w-1': () => {
        calls.push('removed')
        entries = []
        return new Response(null, { status: 204 })
      },
    })
    const link = await screen.findByRole('link', { name: 'Chamar Joana Lima pelo WhatsApp' })
    link.addEventListener('click', (e) => e.preventDefault()) // jsdom não navega
    await user.click(link)
    expect(await screen.findByText(/Chamado em 20\/11, 17:02/)).toBeInTheDocument()
    expect(calls).toEqual(['called'])

    await user.click(screen.getByRole('button', { name: 'Tirar Joana Lima da lista' }))
    await waitFor(() => expect(screen.queryByRole('region', { name: 'Lista de espera' })).not.toBeInTheDocument())
    expect(calls).toEqual(['called', 'removed'])
  })

  it('sem ninguém esperando, a seção não aparece', async () => {
    await open([])
    await screen.findByText('Nenhum agendamento neste dia.')
    expect(screen.queryByRole('region', { name: 'Lista de espera' })).not.toBeInTheDocument()
  })
})
