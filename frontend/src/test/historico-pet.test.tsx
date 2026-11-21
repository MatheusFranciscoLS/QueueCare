import { act, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { addDays, todayIn } from '../api/dates'
import { json, mockApi, PASSWORD, problem, renderWithHarness, token, USER, type Call } from './helpers'

vi.mock('../pages/TodaySummary', () => ({ TodaySummary: () => null }))
vi.mock('../pages/customer/CustomerPanel', () => ({ CustomerPanel: () => null }))
afterEach(() => vi.unstubAllGlobals())

type Routes = Record<string, (call: Call) => Response | Promise<Response>>

const ZONE = 'America/Sao_Paulo'
const today = todayIn(ZONE)

const THOR = { id: 'pet-1', name: 'Thor', species: 'DOG', breed: null, size: 'LARGE', notes: null, active: true }
const visit = (id: string, status: string, daysAgo: number, extra: Record<string, unknown> = {}) => ({
  id,
  startsAt: `${addDays(today, -daysAgo)}T13:00:00Z`,
  endsAt: `${addDays(today, -daysAgo)}T14:00:00Z`,
  status,
  providerId: 'p-1',
  businessName: 'Estúdio Bem-te-vi',
  timeZone: ZONE,
  serviceId: 's-1',
  serviceName: 'Banho',
  priceCents: 7000,
  ...extra,
})

async function login(role: string, routes: Routes) {
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

describe('histórico do pet (tutor)', () => {
  const open = async (routes: Routes) => {
    const ctx = await login('CUSTOMER', { 'GET /api/customer/pets': () => json(200, [THOR]), ...routes })
    await ctx.user.click(within(screen.getByRole('navigation', { name: 'Menu do cliente' })).getByRole('link', { name: 'Meus pets' }))
    await screen.findByRole('heading', { name: 'Meus pets' })
    return ctx
  }

  it('abre o histórico sob demanda, com serviço, estúdio, valor e situação; "Agendar de novo" leva o mesmo serviço', async () => {
    const { calls, user } = await open({
      'GET /api/customer/pets/pet-1/history': () => json(200, [visit('v-2', 'SCHEDULED', -5), visit('v-1', 'COMPLETED', 20, { serviceName: 'Banho e tosa', serviceId: 's-2', priceCents: 9000 })]),
    })
    expect(calls.some((c) => c.path.endsWith('/history'))).toBe(false) // só carrega ao abrir
    await user.click(await screen.findByRole('button', { name: 'Histórico' }))
    const group = await screen.findByRole('group', { name: 'Histórico de Thor' })
    expect(group).toHaveTextContent('Banho · Estúdio Bem-te-vi · R$ 70,00 · agendado')
    expect(group).toHaveTextContent('Banho e tosa · Estúdio Bem-te-vi · R$ 90,00 · concluído')
    const again = within(group).getByRole('link', { name: 'Agendar de novo: Banho e tosa' })
    expect(again).toHaveAttribute('href', '/agendar?estudio=p-1&servico=s-2&pet=pet-1')
    await user.click(screen.getByRole('button', { name: 'Ocultar histórico' }))
    expect(screen.queryByRole('group', { name: 'Histórico de Thor' })).not.toBeInTheDocument()
  })

  it('sem visitas diz isso e não oferece "Agendar de novo"; só cancelado também não oferece', async () => {
    const { user } = await open({ 'GET /api/customer/pets/pet-1/history': () => json(200, []) })
    await user.click(await screen.findByRole('button', { name: 'Histórico' }))
    expect(await screen.findByText('Thor ainda não tem atendimentos.')).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: /Agendar de novo/ })).not.toBeInTheDocument()
  })

  it('pet arquivado mostra o histórico mas não oferece agendar de novo', async () => {
    const ctx = await login('CUSTOMER', {
      'GET /api/customer/pets': () => json(200, [{ ...THOR, active: false }]),
      'GET /api/customer/pets?includeArchived=true': () => json(200, [{ ...THOR, active: false }]),
      'GET /api/customer/pets/pet-1/history': () => json(200, [visit('v-1', 'COMPLETED', 20)]),
    })
    await ctx.user.click(within(screen.getByRole('navigation', { name: 'Menu do cliente' })).getByRole('link', { name: 'Meus pets' }))
    await ctx.user.click(await screen.findByRole('checkbox', { name: /Mostrar arquivados/ }))
    await ctx.user.click(await screen.findByRole('button', { name: 'Histórico' }))
    expect(await screen.findByText(/Banho · Estúdio Bem-te-vi/)).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: /Agendar de novo/ })).not.toBeInTheDocument()
  })

  it('erro ao carregar mostra o motivo', async () => {
    const { user } = await open({ 'GET /api/customer/pets/pet-1/history': () => problem(500, 'Erro inesperado.') })
    await user.click(await screen.findByRole('button', { name: 'Histórico' }))
    expect(await screen.findByRole('alert')).toBeInTheDocument()
  })
})

describe('agendar de novo: escolhas já feitas', () => {
  it('abre com estúdio, serviço e pet escolhidos e vai direto aos horários', async () => {
    const slot = { localStart: `${today}T09:00:00`, startsAt: `${today}T12:00:00Z`, endsAt: `${today}T13:00:00Z` }
    mockApi({
      'POST /api/auth/login': () => token(),
      'GET /api/me': () => json(200, { ...USER, role: 'CUSTOMER', name: 'Maria Souza', phone: '5511912345678' }),
      'GET /api/catalog/providers?page=0&size=50': () => json(200, [{ id: 'p-1', businessName: 'Estúdio Bem-te-vi', timeZone: ZONE }, { id: 'p-2', businessName: 'Outro', timeZone: ZONE }]),
      'GET /api/catalog/providers/p-1/services': () => json(200, [{ id: 's-1', name: 'Banho', durationMinutes: 60, priceCents: 5000, priceLargeCents: 9000 }, { id: 's-2', name: 'Tosa', durationMinutes: 45 }]),
      'GET /api/customer/pets': () => json(200, [THOR, { ...THOR, id: 'pet-2', name: 'Mel', species: 'CAT', size: 'SMALL' }]),
      [`GET /api/providers/p-1/slots?serviceId=s-1&date=${today}&petId=pet-1`]: () => json(200, [slot]),
    })
    const view = renderWithHarness()
    await act(async () => view.api().login('x@exemplo.test', PASSWORD))
    await screen.findByRole('heading', { name: 'Olá, Maria Souza' })
    act(() => view.navigate()('/agendar?estudio=p-1&servico=s-1&pet=pet-1'))
    await waitFor(async () => expect(await screen.findByRole('radio', { name: 'Estúdio Bem-te-vi' })).toBeChecked())
    await waitFor(async () => expect(await screen.findByRole('radio', { name: /^Banho/ })).toBeChecked())
    const user = userEvent.setup()
    await user.click(await screen.findByRole('button', { name: '09:00' }))
    const confirm = screen.getByRole('region', { name: 'Confirmar agendamento' })
    expect(confirm).toHaveTextContent('para Thor') // o pet pedido, mesmo havendo dois
    expect(confirm).toHaveTextContent('Valor: R$ 90,00') // porte grande
    expect(screen.queryByRole('radio', { name: 'Tosa · 45 min · a combinar' })).toBeInTheDocument() // as outras opções continuam livres
  })
})

describe('visitas anteriores na agenda (equipe)', () => {
  const item = (pet: Record<string, unknown> | null) => ({
    id: 'a-1',
    startsAt: `${today}T14:00:00Z`,
    endsAt: `${today}T15:00:00Z`,
    status: 'CHECKED_IN',
    serviceId: 's-1',
    serviceName: 'Banho',
    customerId: 'c-1',
    customerName: 'Marina Souza',
    pet,
  })

  it('mostra as visitas anteriores do pet sob demanda', async () => {
    const { calls, user } = await login('PROVIDER', {
      [`GET /api/agenda?date=${today}`]: () => json(200, [item({ name: 'Thor', species: 'DOG' })]),
      'GET /api/appointments/a-1/pet-visits': () =>
        json(200, [
          { startsAt: `${addDays(today, -15)}T13:00:00Z`, serviceName: 'Banho', priceCents: 7000 },
          { startsAt: `${addDays(today, -45)}T13:00:00Z`, serviceName: 'Banho e tosa', priceCents: null },
        ]),
    })
    await user.click(within(screen.getByRole('navigation', { name: 'Menu do prestador' })).getByRole('link', { name: 'Agenda' }))
    await screen.findByText('Marina Souza')
    expect(calls.some((c) => c.path.endsWith('/pet-visits'))).toBe(false)
    await user.click(screen.getByRole('button', { name: 'Visitas anteriores' }))
    const group = await screen.findByRole('group', { name: 'Visitas anteriores de Thor' })
    const lines = within(group).getAllByRole('listitem')
    expect(lines[0]).toHaveTextContent('Banho · R$ 70,00')
    expect(lines[1]).toHaveTextContent('Banho e tosa')
    expect(lines[1]).not.toHaveTextContent('R$')
  })

  it('sem visitas diz isso; agendamento sem pet não tem o botão', async () => {
    const { user } = await login('PROVIDER', {
      [`GET /api/agenda?date=${today}`]: () => json(200, [item({ name: 'Thor', species: 'DOG' }), { ...item(null), id: 'a-2', customerName: 'Carla Mendes' }]),
      'GET /api/appointments/a-1/pet-visits': () => json(200, []),
    })
    await user.click(within(screen.getByRole('navigation', { name: 'Menu do prestador' })).getByRole('link', { name: 'Agenda' }))
    await screen.findByText('Carla Mendes')
    expect(screen.getAllByRole('button', { name: 'Visitas anteriores' })).toHaveLength(1)
    await user.click(screen.getByRole('button', { name: 'Visitas anteriores' }))
    expect(await screen.findByText('Thor não tem visitas anteriores aqui.')).toBeInTheDocument()
  })
})
