import { act, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { addDays, todayIn } from '../api/dates'
import { dayLabel, periodLabel } from '../pages/provider/TimeOffSection'
import { json, mockApi, PASSWORD, problem, renderWithHarness, token, USER, type Call } from './helpers'

vi.mock('../pages/customer/CustomerPanel', () => ({ CustomerPanel: () => null }))
vi.mock('../pages/provider/SetupPanel', () => ({ SetupPanel: () => null }))
vi.mock('../pages/TodaySummary', () => ({ TodaySummary: () => null }))
afterEach(() => vi.unstubAllGlobals())

type Routes = Record<string, (call: Call) => Response | Promise<Response>>

const ZONE = 'America/Sao_Paulo'
const today = todayIn(ZONE)

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

describe('folgas (funções)', () => {
  it('escreve o dia e o período no padrão brasileiro', () => {
    expect(dayLabel('2026-12-25')).toBe('25/12/2026')
    expect(periodLabel({ startsOn: '2026-12-25', endsOn: '2026-12-25' })).toBe('25/12/2026')
    expect(periodLabel({ startsOn: '2026-12-20', endsOn: '2027-01-05' })).toBe('20/12/2026 a 05/01/2027')
  })
})

describe('Expediente: folgas e feriados', () => {
  /** O aviso de resultado da seção (role status), com o texto já preenchido. */
  const findNotice = () => waitFor(() => { const n = within(screen.getByRole('region', { name: 'Folgas e feriados' })).getByRole('status'); expect(n).not.toBeEmptyDOMElement(); return n })
  const AVAILABILITY = { 'GET /api/provider/availability': () => json(200, []) }

  async function openExpediente(routes: Routes) {
    const ctx = await loginAs('PROVIDER', { ...AVAILABILITY, ...routes })
    await ctx.user.click(within(screen.getByRole('navigation', { name: 'Menu do prestador' })).getByRole('link', { name: 'Expediente' }))
    await screen.findByRole('heading', { name: 'Folgas e feriados' })
    return ctx
  }

  it('lista o que vem pela frente e fecha um período sem cancelar nada, avisando dos agendamentos que já existiam', async () => {
    let listed = [{ id: 't-1', startsOn: addDays(today, 5), endsOn: addDays(today, 5), reason: 'Feriado' }]
    const { calls, user } = await openExpediente({
      'GET /api/provider/time-off': () => json(200, listed),
      'POST /api/provider/time-off': () => {
        listed = [...listed, { id: 't-2', startsOn: addDays(today, 10), endsOn: addDays(today, 12), reason: 'Férias' }]
        return json(201, { ...listed[1], affectedAppointments: 2 })
      },
    })
    expect(await screen.findByText(dayLabel(addDays(today, 5)))).toBeInTheDocument()
    expect(screen.getByText(/Feriado/)).toBeInTheDocument()

    await user.type(screen.getByLabelText('Primeiro dia'), addDays(today, 10))
    await user.type(screen.getByLabelText('Último dia'), addDays(today, 12))
    await user.type(screen.getByLabelText('Motivo (opcional)'), '  Férias ')
    await user.click(screen.getByRole('button', { name: 'Fechar esses dias' }))

    const notice = await findNotice()
    expect(notice).toHaveTextContent('Já havia 2 agendamentos nesse período: eles continuam marcados.')
    expect(within(notice).getByRole('link', { name: 'Ver na Agenda' })).toHaveAttribute('href', `/agenda?data=${addDays(today, 10)}`)
    expect(calls.find((c) => c.method === 'POST' && c.path === '/api/provider/time-off')?.body).toEqual({ startsOn: addDays(today, 10), endsOn: addDays(today, 12), reason: 'Férias' })
    expect(await screen.findByText(periodLabel(listed[1]))).toBeInTheDocument() // a lista foi recarregada
    expect(screen.getByLabelText('Primeiro dia')).toHaveValue('') // o formulário limpou
  })

  it('"Último dia" vazio fecha só o primeiro dia, e sem agendamentos a mensagem não fala deles', async () => {
    const { calls, user } = await openExpediente({
      'GET /api/provider/time-off': () => json(200, []),
      'POST /api/provider/time-off': (c) => json(201, { id: 't-1', ...(c.body as object), affectedAppointments: 0 }),
    })
    expect(await screen.findByText('Nenhuma folga marcada.')).toBeInTheDocument()
    await user.type(screen.getByLabelText('Primeiro dia'), addDays(today, 3))
    await user.click(screen.getByRole('button', { name: 'Fechar esses dias' }))

    const notice = await findNotice()
    expect(notice).not.toHaveTextContent('agendamento')
    expect(calls.find((c) => c.method === 'POST' && c.path === '/api/provider/time-off')?.body).toEqual({ startsOn: addDays(today, 3), endsOn: addDays(today, 3) })
  })

  it('explica o que falta ou está errado ANTES de enviar e não chama a API', async () => {
    const { calls, user } = await openExpediente({ 'GET /api/provider/time-off': () => json(200, []) })
    await user.click(screen.getByRole('button', { name: 'Fechar esses dias' }))
    expect(await screen.findByText('Informe o primeiro dia fechado.')).toBeInTheDocument()

    await user.type(screen.getByLabelText('Primeiro dia'), addDays(today, 6))
    await user.type(screen.getByLabelText('Último dia'), addDays(today, 4))
    await user.click(screen.getByRole('button', { name: 'Fechar esses dias' }))
    expect(await screen.findByText('O último dia não pode ser antes do primeiro.')).toBeInTheDocument()
    expect(calls.some((c) => c.method === 'POST' && c.path === '/api/provider/time-off')).toBe(false)
  })

  it('mostra o motivo da recusa da API no campo certo (sobreposição)', async () => {
    const { user } = await openExpediente({
      'GET /api/provider/time-off': () => json(200, []),
      'POST /api/provider/time-off': () => problem(400, 'configuração inválida', { errors: { dates: 'já existe uma folga que se sobrepõe a esse período' } }),
    })
    await user.type(screen.getByLabelText('Primeiro dia'), addDays(today, 6))
    await user.click(screen.getByRole('button', { name: 'Fechar esses dias' }))
    expect(await screen.findByText('já existe uma folga que se sobrepõe a esse período')).toBeInTheDocument()
  })

  it('remove uma folga e avisa que o dia volta a receber agendamentos', async () => {
    let listed = [{ id: 't-1', startsOn: addDays(today, 5), endsOn: addDays(today, 6), reason: null as string | null }]
    const { calls, user } = await openExpediente({
      'GET /api/provider/time-off': () => json(200, listed),
      'DELETE /api/provider/time-off/t-1': () => {
        listed = []
        return new Response(null, { status: 204 })
      },
    })
    await user.click(await screen.findByRole('button', { name: `Remover a folga de ${periodLabel(listed[0])}` }))

    expect(await screen.findByText(/removida: o dia volta a receber agendamentos\./)).toBeInTheDocument()
    expect(calls.some((c) => c.method === 'DELETE' && c.path === '/api/provider/time-off/t-1')).toBe(true)
    expect(await screen.findByText('Nenhuma folga marcada.')).toBeInTheDocument()
  })
})

describe('Agendar: dia fechado', () => {
  const NO_DAYS: Routes = {}
  for (let i = 0; i < 14; i++) NO_DAYS[`GET /api/providers/p-1/slots?serviceId=s-1&date=${addDays(today, i)}&petId=pet-1`] = () => json(200, [])

  const open = async (closures: Routes) => {
    const ctx = await loginAs('CUSTOMER', {
      'GET /api/catalog/providers?page=0&size=50': () => json(200, [{ id: 'p-1', businessName: 'Estúdio Pelo e Ponto', timeZone: ZONE, cancellationDeadlineMinutes: 720, phone: null }]),
      'GET /api/catalog/providers/p-1/services': () => json(200, [{ id: 's-1', name: 'Banho', durationMinutes: 60 }]),
      'GET /api/customer/pets': () => json(200, [{ id: 'pet-1', name: 'Rex', species: 'DOG', breed: null, size: 'SMALL', notes: null, active: true }]),
      ...NO_DAYS,
      ...closures,
    })
    await ctx.user.click(within(screen.getByRole('navigation', { name: 'Menu do cliente' })).getByRole('link', { name: 'Agendar' }))
    await ctx.user.click(await screen.findByRole('radio', { name: /Banho/ }))
    return ctx
  }

  it('diz que o estúdio está fechado, com o motivo, e NÃO oferece a lista de espera', async () => {
    await open({ 'GET /api/catalog/providers/p-1/closures': () => json(200, [{ startsOn: addDays(today, -1), endsOn: addDays(today, 20), reason: 'Férias coletivas' }]) })
    expect(await screen.findByText('O estúdio está fechado neste dia (Férias coletivas). Escolha outro dia.')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Avise-me se vagar um horário neste dia' })).not.toBeInTheDocument()
    expect(screen.queryByText('Nenhum horário livre neste dia. Tente outro dia.')).not.toBeInTheDocument()
  })

  it('sem fechamento no dia, o aviso é o de sempre e a lista de espera continua', async () => {
    await open({ 'GET /api/catalog/providers/p-1/closures': () => json(200, [{ startsOn: addDays(today, 40), endsOn: addDays(today, 41), reason: null }]) })
    expect(await screen.findByText('Nenhum horário livre neste dia. Tente outro dia.')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Avise-me se vagar um horário neste dia' })).toBeInTheDocument()
  })

  it('se a consulta dos fechamentos falhar, vale o aviso de sempre (nada quebra)', async () => {
    await open({ 'GET /api/catalog/providers/p-1/closures': () => problem(500, 'erro') })
    expect(await screen.findByText('Nenhum horário livre neste dia. Tente outro dia.')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Avise-me se vagar um horário neste dia' })).toBeInTheDocument()
  })
})

describe('Agenda: aviso de dia fechado', () => {
  const workplace = (closures: object[]) => ({ providerId: 'p-1', businessName: 'Estúdio', timeZone: ZONE, cancellationDeadlineMinutes: 720, closures })

  async function openAgenda(closures: object[]) {
    const ctx = await loginAs('ATTENDANT', {
      'GET /api/agenda/workplace': () => json(200, workplace(closures)),
      ['GET /api/agenda?date=' + today]: () => json(200, []),
      ['GET /api/agenda/waitlist?date=' + today]: () => json(200, []),
    })
    await ctx.user.click(within(screen.getByRole('navigation')).getByRole('link', { name: 'Agenda' }))
    await screen.findByRole('heading', { level: 2 })
    return ctx
  }

  it('avisa que o estúdio está fechado hoje, com o motivo', async () => {
    await openAgenda([{ id: 't-1', startsOn: addDays(today, -1), endsOn: addDays(today, 1), reason: 'Feriado' }])
    expect(await screen.findByText('Estúdio fechado neste dia: Feriado.')).toBeInTheDocument()
  })

  it('sem período cobrindo o dia, não há aviso', async () => {
    await openAgenda([{ id: 't-1', startsOn: addDays(today, 3), endsOn: addDays(today, 4), reason: 'Feriado' }])
    expect(screen.queryByText(/Estúdio fechado neste dia/)).not.toBeInTheDocument()
  })
})
