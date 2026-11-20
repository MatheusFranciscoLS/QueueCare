import { act, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { todayIn } from '../api/dates'
import { formatMoney, moneyInput, parseMoney, priceForSize, priceSummary } from '../api/money'
import { json, mockApi, PASSWORD, problem, renderWithHarness, token, USER, type Call } from './helpers'

vi.mock('../pages/TodaySummary', () => ({ TodaySummary: () => null }))
afterEach(() => vi.unstubAllGlobals())

type Routes = Record<string, (call: Call) => Response | Promise<Response>>

const ZONE = 'America/Sao_Paulo'
const today = todayIn(ZONE)

describe('dinheiro (funções)', () => {
  it('formata centavos em reais', () => {
    expect(formatMoney(5000)).toBe('R$ 50,00')
    expect(formatMoney(123456)).toBe('R$ 1.234,56')
    expect(formatMoney(0)).toBe('R$ 0,00')
    expect(formatMoney(4990)).toBe('R$ 49,90')
  })

  it('lê o valor digitado em centavos, com vírgula, ponto, milhar e "R$"', () => {
    expect(parseMoney('50')).toBe(5000)
    expect(parseMoney('49,9')).toBe(4990)
    expect(parseMoney('49,90')).toBe(4990)
    expect(parseMoney('R$ 50')).toBe(5000)
    expect(parseMoney('1.234,56')).toBe(123456)
    expect(parseMoney('50.5')).toBe(5050)
    expect(parseMoney('0')).toBe(0)
    expect(parseMoney('100000')).toBe(10_000_000)
  })

  it('vazio é "a combinar"; o que não é dinheiro ou passa do teto é inválido', () => {
    expect(parseMoney('')).toBeNull()
    expect(parseMoney('   ')).toBeNull()
    for (const bad of ['abc', '-5', '12,345', '100000,01', '1e3', '5,', '50 reais']) expect(parseMoney(bad), bad).toBeUndefined()
  })

  it('centavos voltam ao campo; preço por porte segue a regra da API', () => {
    expect(moneyInput(5000)).toBe('50,00')
    expect(moneyInput(null)).toBe('')
    const p = { priceCents: 5000, priceMediumCents: 7000, priceLargeCents: null }
    expect(priceForSize(p, 'SMALL')).toBe(5000)
    expect(priceForSize(p, null)).toBe(5000)
    expect(priceForSize(p, 'MEDIUM')).toBe(7000)
    expect(priceForSize(p, 'LARGE')).toBe(5000) // grande sem valor próprio usa o base
    expect(priceForSize({}, 'LARGE')).toBeNull()
  })

  it('resumo do preço do serviço: único, a partir de ou a combinar', () => {
    expect(priceSummary({ priceCents: 5000 })).toBe('R$ 50,00')
    expect(priceSummary({ priceCents: 5000, priceLargeCents: 9000 })).toBe('a partir de R$ 50,00')
    expect(priceSummary({})).toBe('a combinar')
  })
})

async function loginAs(role: string, routes: Routes) {
  const { calls } = mockApi({
    'POST /api/auth/login': () => token(),
    'GET /api/me': () => json(200, { ...USER, role, name: 'Maria Souza', phone: '5511912345678' }),
    'GET /api/agenda/workplace': () => json(200, { providerId: 'p-1', businessName: 'Estúdio', timeZone: ZONE }),
    ...routes,
  })
  const view = renderWithHarness()
  await act(async () => view.api().login('x@exemplo.test', PASSWORD))
  await screen.findByRole('heading', { name: 'Olá, Maria Souza' })
  return { calls, user: userEvent.setup() }
}

describe('preço no cadastro do serviço', () => {
  const open = async (routes: Routes) => {
    const ctx = await loginAs('PROVIDER', { 'GET /api/provider': () => json(200, { id: 'p-1', businessName: 'Estúdio', timeZone: ZONE, minimumNoticeMinutes: 0, cancellationDeadlineMinutes: 60 }), ...routes })
    await ctx.user.click(within(screen.getByRole('navigation', { name: 'Menu do prestador' })).getByRole('link', { name: 'Serviços' }))
    await screen.findByRole('heading', { name: 'Serviços' })
    return ctx
  }

  it('envia os preços em centavos e a linha mostra o valor por porte', async () => {
    let services: unknown[] = []
    const { calls, user } = await open({
      'GET /api/provider/services': () => json(200, services),
      'POST /api/provider/services': ({ body }) => {
        services = [{ id: 's-1', active: true, ...(body as object) }]
        return json(201, services[0])
      },
    })
    await user.type(await screen.findByLabelText('Nome do serviço'), 'Banho')
    await user.clear(screen.getByLabelText('Duração (minutos)'))
    await user.type(screen.getByLabelText('Duração (minutos)'), '60')
    await user.type(screen.getByLabelText('Preço base (R$, opcional)'), '50')
    await user.type(screen.getByLabelText('Preço para porte médio (R$, opcional)'), '70,50')
    await user.type(screen.getByLabelText('Preço para porte grande (R$, opcional)'), '90')
    await user.click(screen.getByRole('button', { name: 'Adicionar serviço' }))

    expect(await screen.findByText(/P R\$ 50,00 · M R\$ 70,50 · G R\$ 90,00/)).toBeInTheDocument()
    expect(calls.find((c) => c.method === 'POST' && c.path === '/api/provider/services')?.body).toEqual({
      name: 'Banho',
      durationMinutes: 60,
      priceCents: 5000,
      priceMediumCents: 7050,
      priceLargeCents: 9000,
    })
  })

  it('valor inválido não é enviado e mostra o erro no campo; o erro da API também', async () => {
    const { calls, user } = await open({
      'GET /api/provider/services': () => json(200, []),
      'POST /api/provider/services': () => problem(400, 'dados inválidos', { errors: { priceLargeCents: 'entre 0 e 10000000 centavos' } }),
    })
    await user.type(await screen.findByLabelText('Nome do serviço'), 'Banho')
    await user.type(screen.getByLabelText('Preço base (R$, opcional)'), 'abc')
    await user.click(screen.getByRole('button', { name: 'Adicionar serviço' }))
    expect(await screen.findByText('Informe o valor em reais, por exemplo 50 ou 49,90.')).toBeInTheDocument()
    expect(calls.some((c) => c.method === 'POST' && c.path === '/api/provider/services')).toBe(false)

    await user.clear(screen.getByLabelText('Preço base (R$, opcional)'))
    await user.type(screen.getByLabelText('Preço base (R$, opcional)'), '50')
    await user.click(screen.getByRole('button', { name: 'Adicionar serviço' }))
    expect(await screen.findByText('entre 0 e 10000000 centavos')).toBeInTheDocument()
  })

  it('editar traz os preços atuais e uma linha de preço único mostra só o valor', async () => {
    const { user } = await open({
      'GET /api/provider/services': () => json(200, [{ id: 's-1', name: 'Banho', durationMinutes: 60, active: true, priceCents: 5000, priceMediumCents: null, priceLargeCents: null }]),
    })
    expect(await screen.findByText(/R\$ 50,00/)).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Editar' }))
    expect(screen.getAllByLabelText('Preço base (R$, opcional)').some((el) => (el as HTMLInputElement).value === '50,00')).toBe(true)
  })
})

describe('preço no agendamento do cliente', () => {
  const SERVICES = [
    { id: 's-1', name: 'Banho', durationMinutes: 60, priceCents: 5000, priceMediumCents: 7000, priceLargeCents: 9000 },
    { id: 's-2', name: 'Tosa', durationMinutes: 45, priceCents: null, priceMediumCents: null, priceLargeCents: null },
    { id: 's-3', name: 'Hidratação', durationMinutes: 30, priceCents: 3000, priceMediumCents: null, priceLargeCents: null },
  ]
  const pets = (size: string | null) => [{ id: 'pet-1', name: 'Rex', species: 'DOG', breed: null, size, notes: null, active: true }]
  const slot = { localStart: `${today}T09:00:00`, startsAt: `${today}T12:00:00Z`, endsAt: `${today}T13:00:00Z` }

  async function toConfirm(size: string | null, serviceName: RegExp) {
    const ctx = await loginAs('CUSTOMER', {
      'GET /api/catalog/providers?page=0&size=50': () => json(200, [{ id: 'p-1', businessName: 'Estúdio', timeZone: ZONE }]),
      'GET /api/catalog/providers/p-1/services': () => json(200, SERVICES),
      'GET /api/customer/pets': () => json(200, pets(size)),
      [`GET /api/providers/p-1/slots?serviceId=${serviceName.source.startsWith('Banho') ? 's-1' : serviceName.source.startsWith('Tosa') ? 's-2' : 's-3'}&date=${today}`]: () => json(200, [slot]),
    })
    await ctx.user.click(within(screen.getByRole('navigation', { name: 'Menu do cliente' })).getByRole('link', { name: 'Agendar' }))
    await ctx.user.click(await screen.findByRole('radio', { name: 'Estúdio' }))
    return ctx
  }

  it('a lista de serviços mostra o preço (ou "a partir de", ou "a combinar") antes de escolher', async () => {
    await toConfirm('MEDIUM', /Banho/)
    expect(await screen.findByRole('radio', { name: 'Banho · 60 min · a partir de R$ 50,00' })).toBeInTheDocument()
    expect(screen.getByRole('radio', { name: 'Tosa · 45 min · a combinar' })).toBeInTheDocument()
    expect(screen.getByRole('radio', { name: 'Hidratação · 30 min · R$ 30,00' })).toBeInTheDocument()
  })

  it.each([
    ['SMALL', 'R$ 50,00'],
    ['MEDIUM', 'R$ 70,00'],
    ['LARGE', 'R$ 90,00'],
  ])('a confirmação mostra o valor pelo porte do pet (%s)', async (size, expected) => {
    const { user } = await toConfirm(size, /Banho/)
    await user.click(await screen.findByRole('radio', { name: /^Banho/ }))
    await user.click(await screen.findByRole('button', { name: '09:00' }))
    expect(screen.getByRole('region', { name: 'Confirmar agendamento' })).toHaveTextContent(`Valor: ${expected}`)
  })

  it('pet sem porte avisa que o valor pode mudar; serviço a combinar não inventa valor', async () => {
    const { user } = await toConfirm(null, /Banho/)
    await user.click(await screen.findByRole('radio', { name: /^Banho/ }))
    await user.click(await screen.findByRole('button', { name: '09:00' }))
    const region = screen.getByRole('region', { name: 'Confirmar agendamento' })
    expect(region).toHaveTextContent('Valor: R$ 50,00')
    expect(region).toHaveTextContent('Informe o porte do pet para ver o valor exato.')
  })

  it('serviço sem preço mostra "a combinar com o estúdio"', async () => {
    const { user } = await toConfirm('LARGE', /Tosa/)
    await user.click(await screen.findByRole('radio', { name: /^Tosa/ }))
    await user.click(await screen.findByRole('button', { name: '09:00' }))
    expect(screen.getByRole('region', { name: 'Confirmar agendamento' })).toHaveTextContent('Valor: a combinar com o estúdio')
  })
})

describe('valor na agenda, na fila e em Meus agendamentos', () => {
  const item = (extra: Record<string, unknown>) => ({
    id: 'a-1',
    startsAt: `${today}T14:00:00Z`,
    endsAt: `${today}T15:00:00Z`,
    status: 'SCHEDULED',
    serviceId: 's-1',
    serviceName: 'Banho',
    customerId: 'c-1',
    customerName: 'Marina Souza',
    ...extra,
  })

  it('a agenda do dia mostra o valor congelado; sem valor, nada', async () => {
    const { user } = await loginAs('PROVIDER', {
      [`GET /api/agenda?date=${today}`]: () => json(200, [item({ priceCents: 7000 }), item({ id: 'a-2', customerName: 'Carla Mendes', priceCents: null })]),
    })
    await user.click(within(screen.getByRole('navigation', { name: 'Menu do prestador' })).getByRole('link', { name: 'Agenda' }))
    expect(await screen.findByText('R$ 70,00')).toBeInTheDocument()
    expect(screen.getAllByText(/R\$/)).toHaveLength(1)
  })

  it('a fila mostra o valor de quem chegou', async () => {
    const { user } = await loginAs('PROVIDER', {
      'GET /api/agenda/queue': () => json(200, [{ ...item({ priceCents: 9000 }), position: 1, checkedInAt: `${today}T14:00:00Z` }]),
    })
    await user.click(within(screen.getByRole('navigation', { name: 'Menu do prestador' })).getByRole('link', { name: 'Fila' }))
    expect(await screen.findByText('R$ 90,00')).toBeInTheDocument()
  })

  it('Meus agendamentos mostra o valor da reserva', async () => {
    const { user } = await loginAs('CUSTOMER', {
      'GET /api/appointments/mine?page=0&size=50': () =>
        json(200, [{ id: 'a-1', providerId: 'p-1', serviceId: 's-1', startsAt: `${today}T14:00:00Z`, endsAt: `${today}T15:00:00Z`, status: 'SCHEDULED', businessName: 'Estúdio', timeZone: ZONE, serviceName: 'Banho', priceCents: 7000, pet: { name: 'Rex', species: 'DOG' } }]),
    })
    await user.click(within(screen.getByRole('navigation', { name: 'Menu do cliente' })).getByRole('link', { name: 'Meus agendamentos' }))
    expect(await screen.findByText(/Rex \(Cachorro\) · R\$ 70,00/)).toBeInTheDocument()
  })
})
