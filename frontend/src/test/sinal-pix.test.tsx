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

describe('Meu negócio: sinal por Pix', () => {
  const BUSINESS = { id: 'p-1', businessName: 'Estúdio', timeZone: ZONE, minimumNoticeMinutes: 0, cancellationDeadlineMinutes: 720, phone: '5511912345678', depositRule: 'NEVER' }
  const open = async (routes: Routes, business: object = {}) => {
    const ctx = await loginAs('PROVIDER', { 'GET /api/provider': () => json(200, { ...BUSINESS, ...business }), ...routes })
    await ctx.user.click(within(screen.getByRole('navigation', { name: 'Menu do prestador' })).getByRole('link', { name: 'Meu negócio' }))
    await screen.findByRole('heading', { name: 'Meu negócio' })
    return ctx
  }

  it('começa desligado e mostra a explicação de que não há cobrança pelo app', async () => {
    await open({})
    const section = await screen.findByRole('region', { name: 'Sinal por Pix' })
    expect(within(section).getByLabelText('Quando pedir sinal')).toHaveValue('NEVER')
    expect(within(section).getByText(/Não há cobrança nem multa pelo app/)).toBeInTheDocument()
  })

  it('liga a regra com chave e valor em centavos e confirma', async () => {
    const { calls, user } = await open({ 'PUT /api/provider/deposit': ({ body }) => json(200, { ...BUSINESS, ...(body as object) }) })
    await user.selectOptions(await screen.findByLabelText('Quando pedir sinal'), 'NEW_CUSTOMERS')
    await user.type(screen.getByLabelText('Chave Pix'), 'estudio@exemplo.test')
    await user.type(screen.getByLabelText('Valor do sinal (R$)'), '30')
    await user.click(screen.getByRole('button', { name: 'Salvar sinal' }))

    expect(await screen.findByText('Configuração do sinal salva.')).toBeVisible()
    expect(calls.find((c) => c.method === 'PUT' && c.path === '/api/provider/deposit')?.body).toEqual({ pixKey: 'estudio@exemplo.test', depositCents: 3000, depositRule: 'NEW_CUSTOMERS' })
  })

  it('regra ligada sem chave ou sem valor não é enviada e mostra o erro no campo', async () => {
    const { calls, user } = await open({})
    await user.selectOptions(await screen.findByLabelText('Quando pedir sinal'), 'NEW_OR_REPEAT_NO_SHOW')
    await user.click(screen.getByRole('button', { name: 'Salvar sinal' }))
    expect(await screen.findByText('Informe a chave Pix para pedir sinal.')).toBeInTheDocument()
    expect(screen.getByText('Informe o valor do sinal para pedir sinal.')).toBeInTheDocument()
    expect(calls.some((c) => c.method === 'PUT')).toBe(false)
  })

  it('valor inválido não é enviado; o erro da API aparece no campo', async () => {
    const { calls, user } = await open({ 'PUT /api/provider/deposit': () => problem(400, 'dados inválidos', { errors: { pixKey: 'até 140 caracteres, sem quebras de linha' } }) })
    await user.type(await screen.findByLabelText('Valor do sinal (R$)'), 'abc')
    await user.click(screen.getByRole('button', { name: 'Salvar sinal' }))
    expect(await screen.findByText('Informe o valor em reais, por exemplo 30 ou 29,90.')).toBeInTheDocument()
    expect(calls.some((c) => c.method === 'PUT')).toBe(false)

    await user.clear(screen.getByLabelText('Valor do sinal (R$)'))
    await user.type(screen.getByLabelText('Valor do sinal (R$)'), '30')
    await user.click(screen.getByRole('button', { name: 'Salvar sinal' }))
    expect(await screen.findByText('até 140 caracteres, sem quebras de linha')).toBeInTheDocument()
  })

  it('traz os valores atuais do servidor', async () => {
    await open({}, { depositRule: 'NEW_CUSTOMERS', pixKey: 'chave-aleatoria', depositCents: 4990 })
    expect(await screen.findByLabelText('Chave Pix')).toHaveValue('chave-aleatoria')
    expect(screen.getByLabelText('Valor do sinal (R$)')).toHaveValue('49,90')
    expect(screen.getByLabelText('Quando pedir sinal')).toHaveValue('NEW_CUSTOMERS')
  })
})

describe('agendar: sinal antes de confirmar', () => {
  const slot = { localStart: today + 'T09:00:00', startsAt: today + 'T12:00:00Z', endsAt: today + 'T13:00:00Z' }
  const book = async (terms: object | null, extra: Routes = {}) => {
    const { user, calls } = await loginAs('CUSTOMER', {
      'GET /api/catalog/providers?page=0&size=50': () => json(200, [{ id: 'p-1', businessName: 'Estúdio Pelo e Ponto', timeZone: ZONE, cancellationDeadlineMinutes: 720, phone: '5511912345678' }]),
      'GET /api/catalog/providers/p-1/services': () => json(200, [{ id: 's-1', name: 'Banho', durationMinutes: 60 }]),
      'GET /api/customer/pets': () => json(200, [{ id: 'pet-1', name: 'Rex', species: 'DOG', breed: null, size: 'SMALL', notes: null, active: true }]),
      'GET /api/catalog/providers/p-1/deposit-terms': () => (terms ? json(200, terms) : problem(500, 'x')),
      ['GET /api/providers/p-1/slots?serviceId=s-1&date=' + today + '&petId=pet-1']: () => json(200, [slot]),
      ...extra,
    })
    await user.click(within(screen.getByRole('navigation', { name: 'Menu do cliente' })).getByRole('link', { name: 'Agendar' }))
    await user.click(await screen.findByRole('radio', { name: /Banho/ }))
    await user.click(await screen.findByRole('button', { name: '09:00' }))
    return { user, calls }
  }

  it('estúdio que pede sinal deste tutor: mostra valor e chave antes de confirmar', async () => {
    await book({ required: true, depositCents: 3000, pixKey: 'estudio@exemplo.test' })
    expect(await screen.findByText(/sinal de R\$ 30,00/)).toBeInTheDocument()
    expect(screen.getByText('estudio@exemplo.test')).toBeInTheDocument()
    expect(screen.getByText(/Depois de reservar, envie o comprovante ao estúdio/)).toBeInTheDocument()
  })

  it('sem sinal para este tutor, ou sem conseguir saber, a confirmação segue sem aviso', async () => {
    await book({ required: false })
    await screen.findByRole('button', { name: 'Confirmar agendamento' })
    expect(screen.queryByText(/sinal de/)).not.toBeInTheDocument()
  })

  it('falha ao consultar o sinal não trava a reserva (o servidor decide e a tela mostra depois)', async () => {
    await book(null)
    expect(await screen.findByRole('button', { name: 'Confirmar agendamento' })).toBeEnabled()
    expect(screen.queryByText(/sinal de/)).not.toBeInTheDocument()
  })

  it('depois de reservar com sinal pendente, a tela de confirmação lembra e leva ao WhatsApp do estúdio', async () => {
    const { user } = await book(
      { required: true, depositCents: 3000, pixKey: 'estudio@exemplo.test' },
      {
        'POST /api/appointments': () =>
          json(201, { id: 'a-1', providerId: 'p-1', serviceId: 's-1', startsAt: slot.startsAt, endsAt: slot.endsAt, status: 'SCHEDULED', businessName: 'Estúdio Pelo e Ponto', timeZone: ZONE, serviceName: 'Banho', depositCents: 3000, depositStatus: 'REQUESTED', studioPixKey: 'estudio@exemplo.test' }),
      },
    )
    await user.click(await screen.findByRole('button', { name: 'Confirmar agendamento' }))
    expect(await screen.findByRole('heading', { name: 'Agendamento confirmado' })).toBeInTheDocument()
    expect(screen.getByText(/Falta o sinal de/)).toHaveTextContent('R$ 30,00')
    const link = screen.getByRole('link', { name: 'Enviar comprovante pelo WhatsApp' })
    expect(link.getAttribute('href')).toMatch(/^https:\/\/wa\.me\/5511912345678\?text=/)
  })
})

describe('meus agendamentos: sinal', () => {
  const MINE = 'GET /api/appointments/mine?page=0&size=50'
  const appt = (extra: object) => ({
    id: 'a-1',
    providerId: 'p-1',
    serviceId: 's-1',
    startsAt: '2099-12-10T13:00:00Z',
    endsAt: '2099-12-10T14:00:00Z',
    status: 'SCHEDULED',
    businessName: 'Estúdio',
    timeZone: ZONE,
    serviceName: 'Banho',
    studioPhone: '5511912345678',
    ...extra,
  })
  const open = async (items: object[]) => {
    const ctx = await loginAs('CUSTOMER', { [MINE]: () => json(200, items) })
    await ctx.user.click(within(screen.getByRole('navigation', { name: 'Menu do cliente' })).getByRole('link', { name: 'Meus agendamentos' }))
    return ctx
  }

  it('sinal pendente mostra valor, chave e o atalho do comprovante', async () => {
    await open([appt({ depositCents: 3000, depositStatus: 'REQUESTED', studioPixKey: 'estudio@exemplo.test' })])
    expect(await screen.findByText('Sinal pendente: R$ 30,00 por Pix.')).toBeInTheDocument()
    expect(screen.getByText(/Chave: estudio@exemplo.test/)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Enviar comprovante' }).getAttribute('href')).toMatch(/^https:\/\/wa\.me\/5511912345678\?text=/)
  })

  it('sinal recebido aparece, e agendamento cancelado não mostra sinal', async () => {
    await open([appt({ depositCents: 3000, depositStatus: 'RECEIVED' }), appt({ id: 'a-2', status: 'CANCELLED', depositCents: 3000, depositStatus: 'REQUESTED', studioPixKey: 'x' })])
    expect(await screen.findByText('Sinal recebido.')).toBeInTheDocument()
    expect(screen.queryByText(/Sinal pendente/)).not.toBeInTheDocument()
  })

  it('sem sinal, nada a mais na linha', async () => {
    await open([appt({ depositStatus: 'NONE' })])
    await screen.findByText('Agendado')
    expect(screen.queryByText(/Sinal/)).not.toBeInTheDocument()
  })
})

describe('agenda: sinal para a equipe', () => {
  const AGENDA = 'GET /api/agenda?date=' + today
  const item = (id: string, extra: object = {}) => ({
    id,
    startsAt: hoursFromNow(3),
    endsAt: hoursFromNow(4),
    status: 'SCHEDULED',
    serviceId: 's-1',
    serviceName: 'Banho',
    customerId: 'c-1',
    customerName: 'Joana Lima',
    customerNoShows: 0,
    pet: { name: 'Mel', species: 'DOG', breed: null, size: 'MEDIUM', notes: null },
    ...extra,
  })
  const open = async (routes: Routes) => {
    mockApi({
      'POST /api/auth/login': () => token(),
      'GET /api/me': () => json(200, { ...USER, role: 'ATTENDANT', name: 'Maria Souza' }),
      'GET /api/agenda/workplace': () => json(200, { providerId: 'p-1', businessName: 'Estúdio', timeZone: ZONE, cancellationDeadlineMinutes: 720 }),
      ...routes,
    })
    const view = renderWithHarness()
    await act(async () => view.api().login('x@exemplo.test', PASSWORD))
    await screen.findByRole('heading', { name: 'Olá, Maria Souza' })
    const user = userEvent.setup()
    await user.click(within(screen.getByRole('navigation')).getByRole('link', { name: 'Agenda' }))
    return user
  }

  it('mostra "Sinal pendente" e "Sinal recebido" por linha, só com botão no pendente', async () => {
    await open({ [AGENDA]: () => json(200, [item('a-1', { depositCents: 3000, depositStatus: 'REQUESTED' }), item('a-2', { customerName: 'Pedro Alves', depositCents: 3000, depositStatus: 'RECEIVED' }), item('a-3', { customerName: 'Ana Costa', depositStatus: 'NONE' })]) })
    const rows = await screen.findAllByRole('listitem')
    expect(within(rows[0]).getByText('Sinal pendente · R$ 30,00')).toBeInTheDocument()
    expect(within(rows[0]).getByRole('button', { name: 'Marcar sinal de Joana Lima como recebido' })).toBeInTheDocument()
    expect(within(rows[1]).getByText('Sinal recebido')).toBeInTheDocument()
    expect(within(rows[1]).queryByRole('button', { name: /Marcar sinal/ })).not.toBeInTheDocument()
    expect(within(rows[2]).queryByText(/Sinal/)).not.toBeInTheDocument()
  })

  it('marcar recebido chama a API, avisa e atualiza a linha', async () => {
    let received = false
    const user = await open({
      [AGENDA]: () => json(200, [item('a-1', received ? { depositCents: 3000, depositStatus: 'RECEIVED' } : { depositCents: 3000, depositStatus: 'REQUESTED' })]),
      'POST /api/appointments/a-1/deposit-received': () => {
        received = true
        return json(200, { id: 'a-1', depositStatus: 'RECEIVED' })
      },
    })
    await user.click(await screen.findByRole('button', { name: 'Marcar sinal de Joana Lima como recebido' }))
    expect(await screen.findByText('Sinal de Joana Lima registrado como recebido.')).toBeInTheDocument()
    await waitFor(() => expect(screen.getByText('Sinal recebido')).toBeInTheDocument())
    expect(screen.queryByRole('button', { name: /Marcar sinal/ })).not.toBeInTheDocument()
  })
})
