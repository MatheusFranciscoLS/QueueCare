import { act, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { todayIn } from '../api/dates'
import { firstName, reminderMessage } from '../api/policy'
import { json, mockApi, PASSWORD, problem, renderWithHarness, token, USER, type Call } from './helpers'

vi.mock('../pages/customer/CustomerPanel', () => ({ CustomerPanel: () => null }))
vi.mock('../pages/provider/SetupPanel', () => ({ SetupPanel: () => null }))
vi.mock('../pages/TodaySummary', () => ({ TodaySummary: () => null }))
afterEach(() => vi.unstubAllGlobals())

type Routes = Record<string, (call: Call) => Response | Promise<Response>>

const ZONE = 'America/Sao_Paulo'
const today = todayIn(ZONE)
const hoursFromNow = (h: number) => new Date(Date.now() + h * 3_600_000).toISOString()

describe('mensagem do lembrete (funções)', () => {
  const base = { tutorName: 'Joana Lima', studioName: 'Estúdio Pelo e Ponto', serviceName: 'Banho', petName: 'Mel', startsAt: '2026-12-10T13:00:00Z', timeZone: ZONE }

  it('primeiro nome para cumprimentar', () => {
    expect(firstName('Joana Lima')).toBe('Joana')
    expect(firstName('  Ana  ')).toBe('Ana')
    expect(firstName(null)).toBe('')
    expect(firstName('')).toBe('')
  })

  it('leva estúdio, serviço, pet, horário, pedido de confirmação e até quando mudar de graça, sem artigos que errariam o gênero', () => {
    const text = reminderMessage({ ...base, deadlineMinutes: 720 })
    expect(text).toBe(
      'Olá, Joana! Aqui é Estúdio Pelo e Ponto. Lembrete do seu agendamento: Banho de Mel, quinta-feira, 10 de dezembro de 2026, às 10:00. Você confirma que vem? Se precisar mudar, avise até quarta-feira, 09 de dezembro de 2026, às 22:00.',
    )
    expect(text).not.toMatch(/\b(do|da) (Banho|Mel|Estúdio)/)
  })

  it('sem prazo ou sem nome, a mensagem continua natural', () => {
    expect(reminderMessage({ ...base, deadlineMinutes: 0, tutorName: null, petName: null })).toBe(
      'Olá! Aqui é Estúdio Pelo e Ponto. Lembrete do seu agendamento: Banho, quinta-feira, 10 de dezembro de 2026, às 10:00. Você confirma que vem? Se precisar mudar, é só avisar.',
    )
  })
})

const item = (id: string, startsAt: string, extra: object = {}) => ({
  id,
  startsAt,
  endsAt: new Date(Date.parse(startsAt) + 3_600_000).toISOString(),
  status: 'SCHEDULED',
  serviceId: 's-1',
  serviceName: 'Banho',
  customerId: 'c-1',
  customerName: 'Joana Lima',
  customerPhone: '5511912345678',
  customerNoShows: 0,
  remindedAt: null,
  pet: { name: 'Mel', species: 'DOG', breed: null, size: 'MEDIUM', notes: null },
  ...extra,
})

async function openAgenda(routes: Routes) {
  const { calls } = mockApi({
    'POST /api/auth/login': () => token(),
    'GET /api/me': () => json(200, { ...USER, role: 'ATTENDANT', name: 'Maria Souza' }),
    'GET /api/agenda/workplace': () => json(200, { providerId: 'p-1', businessName: 'Estúdio Pelo e Ponto', timeZone: ZONE, cancellationDeadlineMinutes: 720 }),
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

describe('agenda: lembrar pelo WhatsApp', () => {
  it('só oferece "Lembrar" para quem está agendado, ainda não começou e tem telefone', async () => {
    await openAgenda({
      [AGENDA]: () =>
        json(200, [
          item('a-1', hoursFromNow(3)),
          item('a-2', hoursFromNow(4), { customerName: 'Pedro Alves', customerPhone: null }), // sem telefone
          item('a-3', hoursFromNow(-2), { customerName: 'Ana Costa' }), // horário já passou
          item('a-4', hoursFromNow(5), { customerName: 'Rui Dias', status: 'CHECKED_IN' }), // já chegou
        ]),
    })
    const rows = await screen.findAllByRole('listitem')
    expect(within(rows[0]).getByRole('link', { name: 'Lembrar Joana Lima pelo WhatsApp' })).toBeInTheDocument()
    expect(within(rows[1]).queryByRole('link', { name: /Lembrar/ })).not.toBeInTheDocument()
    expect(within(rows[2]).queryByRole('link', { name: /Lembrar/ })).not.toBeInTheDocument()
    expect(within(rows[3]).queryByRole('link', { name: /Lembrar/ })).not.toBeInTheDocument()
  })

  it('o link abre o WhatsApp do tutor com a mensagem pronta (sem prazo e gênero errados) e guarda o lembrete ao clicar', async () => {
    let remindedAt: string | null = null
    const { calls, user } = await openAgenda({
      [AGENDA]: () => json(200, [item('a-1', hoursFromNow(3), { remindedAt })]),
      'POST /api/appointments/a-1/reminded': () => {
        remindedAt = new Date().toISOString()
        return json(200, { id: 'a-1', status: 'SCHEDULED' })
      },
    })
    const link = await screen.findByRole('link', { name: 'Lembrar Joana Lima pelo WhatsApp' })
    expect(link.getAttribute('href')).toMatch(/^https:\/\/wa\.me\/5511912345678\?text=/)
    const text = decodeURIComponent(link.getAttribute('href') ?? '')
    expect(text).toContain('Olá, Joana! Aqui é Estúdio Pelo e Ponto.')
    expect(text).toContain('Banho de Mel')
    expect(text).toContain('Se precisar mudar, avise até')
    expect(link).toHaveAttribute('target', '_blank')
    expect(link).toHaveAttribute('rel', expect.stringContaining('noopener'))

    // jsdom não navega: o clique só dispara o registro do lembrete
    link.addEventListener('click', (e) => e.preventDefault())
    await user.click(link)

    await waitFor(() => expect(calls.filter((c) => c.method === 'POST' && c.path === '/api/appointments/a-1/reminded')).toHaveLength(1))
    expect(await screen.findByText(/Lembrete enviado em \d\d\/\d\d, \d\d:\d\d/)).toBeInTheDocument()
  })

  it('mostra quando já foi lembrado, e uma falha ao guardar aparece sem esconder o link', async () => {
    const { user } = await openAgenda({
      [AGENDA]: () => json(200, [item('a-1', hoursFromNow(3), { remindedAt: '2026-11-20T20:02:00Z' })]),
      'POST /api/appointments/a-1/reminded': () => problem(409, 'operação recusada', { reason: 'INVALID_STATE' }),
    })
    expect(await screen.findByText('Lembrete enviado em 20/11, 17:02')).toBeInTheDocument()
    const link = screen.getByRole('link', { name: 'Lembrar Joana Lima pelo WhatsApp' })
    link.addEventListener('click', (e) => e.preventDefault())
    await user.click(link)
    expect(await screen.findByText('Esse agendamento já mudou de situação. Atualize a agenda.')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Lembrar Joana Lima pelo WhatsApp' })).toBeInTheDocument()
  })
})
