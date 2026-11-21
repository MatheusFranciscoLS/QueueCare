import { act, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { todayIn } from '../api/dates'
import { bookingPolicyText, freeChangeInstant, freeChangePassed, minutesText, studioChangeLink, whenText } from '../api/policy'
import { json, mockApi, PASSWORD, problem, renderWithHarness, token, USER, type Call } from './helpers'

vi.mock('../pages/TodaySummary', () => ({ TodaySummary: () => null }))
vi.mock('../pages/customer/CustomerPanel', () => ({ CustomerPanel: () => null }))
vi.mock('../pages/provider/SetupPanel', () => ({ SetupPanel: () => null }))
afterEach(() => vi.unstubAllGlobals())

type Routes = Record<string, (call: Call) => Response | Promise<Response>>

const ZONE = 'America/Sao_Paulo'
const today = todayIn(ZONE)

describe('política de cancelamento (funções)', () => {
  it('o limite de graça é o início menos o prazo do estúdio', () => {
    expect(freeChangeInstant('2026-12-10T13:00:00Z', 720)).toBe('2026-12-10T01:00:00.000Z')
    expect(freeChangeInstant('2026-12-10T13:00:00Z', 0)).toBe('2026-12-10T13:00:00.000Z')
  })

  it('o texto do limite usa o fuso do negócio', () => {
    // 2026-12-10 13:00Z - 12 h = 01:00Z = 22:00 do dia 9 em São Paulo
    expect(whenText('2026-12-10T01:00:00Z', ZONE)).toBe('quarta-feira, 09 de dezembro de 2026, às 22:00')
    expect(bookingPolicyText('2026-12-10T13:00:00Z', 720, ZONE)).toBe(
      'Você pode cancelar ou remarcar de graça até quarta-feira, 09 de dezembro de 2026, às 22:00. Depois disso, é só falar com o estúdio.',
    )
    expect(bookingPolicyText('2026-12-10T13:00:00Z', 0, ZONE)).toBe('Você pode cancelar ou remarcar de graça até o horário marcado.')
  })

  it('"passou" só com limite informado e já vencido', () => {
    const now = new Date('2026-12-01T12:00:00Z')
    expect(freeChangePassed('2026-12-01T11:59:59Z', now)).toBe(true)
    expect(freeChangePassed('2026-12-01T12:00:01Z', now)).toBe(false)
    expect(freeChangePassed(null, now)).toBe(false)
    expect(freeChangePassed(undefined, now)).toBe(false)
  })

  it('a mensagem ao estúdio leva pet, serviço e horário, sem assumir gênero do pet', () => {
    const link = studioChangeLink('5511912345678', 'Mel', 'Banho', '2026-12-10T13:00:00Z', ZONE)
    expect(link.startsWith('https://wa.me/5511912345678?text=')).toBe(true)
    const text = decodeURIComponent(link.split('?text=')[1])
    expect(text).toContain('agendamento de Mel (Banho)')
    expect(text).toContain('quinta-feira, 10 de dezembro de 2026, às 10:00')
    expect(decodeURIComponent(studioChangeLink('5511912345678', null, null, '2026-12-10T13:00:00Z', ZONE).split('?text=')[1])).toContain('Preciso mudar o agendamento marcado para')
  })

  it('o prazo em minutos vira texto legível', () => {
    expect(minutesText(0)).toBe('até o horário marcado')
    expect(minutesText(45)).toBe('45 min antes')
    expect(minutesText(60)).toBe('1 h antes')
    expect(minutesText(90)).toBe('1 h 30 min antes')
    expect(minutesText(720)).toBe('12 h antes')
    expect(minutesText(1440)).toBe('1 dia antes')
    expect(minutesText(2880)).toBe('2 dias antes')
    expect(minutesText(-1)).toBe('valor inválido')
    expect(minutesText(Number.NaN)).toBe('valor inválido')
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

const MINE = 'GET /api/appointments/mine?page=0&size=50'
const appt = (extra: object) => ({
  id: 'a-1',
  providerId: 'p-1',
  serviceId: 's-1',
  startsAt: '2099-12-10T13:00:00Z',
  endsAt: '2099-12-10T14:00:00Z',
  status: 'SCHEDULED',
  businessName: 'Estúdio Pelo e Ponto',
  timeZone: ZONE,
  serviceName: 'Banho',
  pet: { name: 'Mel', species: 'DOG' },
  ...extra,
})

describe('meus agendamentos: política visível', () => {
  const open = async (routes: Routes) => {
    const ctx = await loginAs('CUSTOMER', routes)
    await ctx.user.click(within(screen.getByRole('navigation', { name: 'Menu do cliente' })).getByRole('link', { name: 'Meus agendamentos' }))
    return ctx
  }

  it('dentro do limite: mostra até quando cancela ou remarca de graça e mantém os botões', async () => {
    await open({ [MINE]: () => json(200, [appt({ freeChangeUntil: '2099-12-09T22:00:00Z', studioPhone: '5511912345678' })]) })
    expect(await screen.findByText(/Cancelar ou remarcar de graça até .*09 de dezembro de 2099, às 19:00./)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Remarcar' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Cancelar agendamento' })).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'Falar com o estúdio' })).not.toBeInTheDocument()
  })

  it('depois do limite: some o botão que a API recusaria e aparece "Falar com o estúdio" com a mensagem pronta', async () => {
    await open({ [MINE]: () => json(200, [appt({ freeChangeUntil: '2020-01-01T10:00:00Z', studioPhone: '5511912345678' })]) })
    const link = await screen.findByRole('link', { name: 'Falar com o estúdio' })
    expect(screen.getByText(/O prazo para cancelar ou remarcar sozinho já passou\./)).toBeInTheDocument()
    expect(link.getAttribute('href')).toMatch(/^https:\/\/wa\.me\/5511912345678\?text=/)
    expect(decodeURIComponent(link.getAttribute('href') ?? '')).toContain('agendamento de Mel (Banho)')
    expect(screen.queryByRole('button', { name: 'Remarcar' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Cancelar agendamento' })).not.toBeInTheDocument()
  })

  it('depois do limite e sem telefone do estúdio: orienta a falar com o estúdio, sem link quebrado', async () => {
    await open({ [MINE]: () => json(200, [appt({ freeChangeUntil: '2020-01-01T10:00:00Z', studioPhone: null })]) })
    expect(await screen.findByText(/Fale com o estúdio\./)).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'Falar com o estúdio' })).not.toBeInTheDocument()
  })

  it('agendamento que não está mais agendado não mostra a política', async () => {
    await open({ [MINE]: () => json(200, [appt({ status: 'COMPLETED', freeChangeUntil: null })]) })
    expect(await screen.findByText('Concluído')).toBeInTheDocument()
    expect(screen.queryByText(/de graça até/)).not.toBeInTheDocument()
    expect(screen.queryByText(/prazo para cancelar/)).not.toBeInTheDocument()
  })
})

describe('agendar: política antes de confirmar', () => {
  const slot = { localStart: today + 'T09:00:00', startsAt: today + 'T12:00:00Z', endsAt: today + 'T13:00:00Z' }
  const book = async (deadline: number | undefined) => {
    const { user } = await loginAs('CUSTOMER', {
      'GET /api/catalog/providers?page=0&size=50': () => json(200, [{ id: 'p-1', businessName: 'Estúdio Pelo e Ponto', timeZone: ZONE, cancellationDeadlineMinutes: deadline, phone: '5511912345678' }]),
      'GET /api/catalog/providers/p-1/services': () => json(200, [{ id: 's-1', name: 'Banho', durationMinutes: 60 }]),
      'GET /api/customer/pets': () => json(200, [{ id: 'pet-1', name: 'Rex', species: 'DOG', breed: null, size: 'SMALL', notes: null, active: true }]),
      ['GET /api/providers/p-1/slots?serviceId=s-1&date=' + today + '&petId=pet-1']: () => json(200, [slot]),
    })
    await user.click(within(screen.getByRole('navigation', { name: 'Menu do cliente' })).getByRole('link', { name: 'Agendar' }))
    await user.click(await screen.findByRole('radio', { name: /Banho/ }))
    await user.click(await screen.findByRole('button', { name: '09:00' }))
  }

  it('a confirmação diz até quando cancelar ou remarcar de graça', async () => {
    await book(720)
    expect(await screen.findByText(/Você pode cancelar ou remarcar de graça até .*, às \d\d:\d\d\. Depois disso, é só falar com o estúdio\./)).toBeInTheDocument()
  })

  it('prazo zero diz "até o horário marcado"', async () => {
    await book(0)
    expect(await screen.findByText('Você pode cancelar ou remarcar de graça até o horário marcado.')).toBeInTheDocument()
  })

  it('sem o prazo na resposta (servidor antigo), a confirmação não inventa regra', async () => {
    await book(undefined)
    await screen.findByRole('button', { name: 'Confirmar agendamento' })
    expect(screen.queryByText(/de graça até/)).not.toBeInTheDocument()
  })
})

describe('Meu negócio: WhatsApp do estúdio e prazo legível', () => {
  const open = async (routes: Routes, business: object = {}) => {
    const ctx = await loginAs('PROVIDER', {
      'GET /api/provider': () => json(200, { id: 'p-1', businessName: 'Estúdio', timeZone: ZONE, minimumNoticeMinutes: 0, cancellationDeadlineMinutes: 720, phone: '5511912345678', ...business }),
      ...routes,
    })
    await ctx.user.click(within(screen.getByRole('navigation', { name: 'Menu do prestador' })).getByRole('link', { name: 'Meu negócio' }))
    await screen.findByRole('heading', { name: 'Meu negócio' })
    return ctx
  }

  it('mostra o telefone formatado e o prazo em horas', async () => {
    await open({})
    expect(screen.getByLabelText('WhatsApp do estúdio (opcional)')).toHaveValue('(11) 91234-5678')
    expect(screen.getByText(/\(12 h antes\)/)).toBeInTheDocument()
  })

  it('envia o telefone digitado e limpa quando vazio', async () => {
    const { calls, user } = await open({ 'PUT /api/provider': ({ body }) => json(200, { id: 'p-1', ...(body as object) }) })
    const field = screen.getByLabelText('WhatsApp do estúdio (opcional)')
    await user.clear(field)
    await user.type(field, '(21) 98888-7777')
    await user.click(screen.getByRole('button', { name: 'Salvar' }))
    await screen.findByText('Salvo.')
    expect(calls.find((c) => c.method === 'PUT')?.body).toMatchObject({ phone: '(21) 98888-7777' })

    await user.clear(screen.getByLabelText('WhatsApp do estúdio (opcional)'))
    await user.click(screen.getByRole('button', { name: 'Salvar' }))
    await screen.findByText('Salvo.')
    expect(calls.filter((c) => c.method === 'PUT').at(-1)?.body).toMatchObject({ phone: '' })
  })

  it('telefone inválido não é enviado e mostra o erro no campo; o erro da API também', async () => {
    const { calls, user } = await open({ 'PUT /api/provider': () => problem(400, 'dados inválidos', { errors: { phone: 'informe DDD e número, por exemplo (11) 91234-5678' } }) })
    const field = screen.getByLabelText('WhatsApp do estúdio (opcional)')
    await user.clear(field)
    await user.type(field, '123')
    await user.click(screen.getByRole('button', { name: 'Salvar' }))
    expect(await screen.findByText(/Informe o DDD e o número/)).toBeInTheDocument()
    expect(calls.some((c) => c.method === 'PUT')).toBe(false)

    await user.clear(field)
    await user.type(field, '(11) 91234-5678')
    await user.click(screen.getByRole('button', { name: 'Salvar' }))
    expect(await screen.findByText('informe DDD e número, por exemplo (11) 91234-5678')).toBeInTheDocument()
  })
})
