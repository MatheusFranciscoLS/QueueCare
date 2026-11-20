import { act, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { todayIn } from '../api/dates'
import { fichaSummary, parseWeight, weightLabel } from '../api/pets'
import { json, mockApi, PASSWORD, problem, renderWithHarness, token, USER, type Call } from './helpers'

vi.mock('../pages/TodaySummary', () => ({ TodaySummary: () => null }))
afterEach(() => vi.unstubAllGlobals())

type Routes = Record<string, (call: Call) => Response | Promise<Response>>

const ZONE = 'America/Sao_Paulo'
const THOR = {
  id: 'pet-1',
  name: 'Thor',
  species: 'DOG',
  breed: 'Golden Retriever',
  size: 'LARGE',
  notes: null,
  coat: 'DOUBLE',
  weightKg: 32.5,
  temperament: 'REACTIVE',
  healthNotes: 'Alergia a shampoo de aveia',
  vaccinated: true,
  active: true,
}

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

async function openPets(routes: Routes) {
  const ctx = await loginAs('CUSTOMER', routes)
  await ctx.user.click(within(screen.getByRole('navigation', { name: 'Menu do cliente' })).getByRole('link', { name: 'Meus pets' }))
  await screen.findByRole('heading', { name: 'Meus pets' })
  return ctx
}

describe('ficha do pet (funções)', () => {
  it('peso digitado com vírgula ou ponto; vazio e inválido são nulos', () => {
    expect(parseWeight('32,5')).toBe(32.5)
    expect(parseWeight(' 12.5 ')).toBe(12.5)
    expect(parseWeight('7')).toBe(7)
    expect(parseWeight('')).toBeNull()
    expect(parseWeight('abc')).toBeNull()
  })

  it('peso no formato brasileiro e resumo só com o que existe', () => {
    expect(weightLabel(32.5)).toBe('32,5 kg')
    expect(fichaSummary({ coat: 'DOUBLE', weightKg: 32.5, vaccinated: true })).toBe('Pelo duplo · 32,5 kg · Vacinas em dia')
    expect(fichaSummary({ coat: null, weightKg: 4, vaccinated: false })).toBe('4 kg · Vacinas pendentes')
    expect(fichaSummary({ coat: null, weightKg: null, vaccinated: null })).toBe('')
  })
})

describe('ficha do pet no cadastro', () => {
  it('envia pelo, peso, comportamento, vacinas e saúde; a lista mostra a ficha', async () => {
    let created = false
    const { calls, user } = await openPets({
      'GET /api/customer/pets': () => json(200, created ? [THOR] : []),
      'POST /api/customer/pets': () => {
        created = true
        return json(201, THOR)
      },
    })
    await screen.findByText('Você ainda não tem pets ativos. Cadastre o primeiro abaixo.')
    await user.type(screen.getByLabelText('Nome do pet'), 'Thor')
    await user.selectOptions(screen.getByLabelText('Espécie'), 'DOG')
    await user.selectOptions(screen.getByLabelText('Pelo (opcional)'), 'DOUBLE')
    await user.type(screen.getByLabelText('Peso em kg (opcional)'), '32,5')
    await user.selectOptions(screen.getByLabelText('Comportamento (opcional)'), 'REACTIVE')
    await user.selectOptions(screen.getByLabelText('Vacinas em dia (opcional)'), 'yes')
    await user.type(screen.getByLabelText('Saúde e alergias (opcional)'), 'Alergia a shampoo de aveia')
    await user.click(screen.getByRole('button', { name: 'Cadastrar pet' }))

    expect(await screen.findByText('Ficha: Pelo duplo · 32,5 kg · Vacinas em dia')).toBeInTheDocument()
    expect(screen.getByText('Saúde e alergias: Alergia a shampoo de aveia')).toBeInTheDocument()
    expect(calls.find((c) => c.method === 'POST' && c.path === '/api/customer/pets')?.body).toMatchObject({
      coat: 'DOUBLE',
      weightKg: 32.5,
      temperament: 'REACTIVE',
      vaccinated: true,
      healthNotes: 'Alergia a shampoo de aveia',
    })
  })

  it('peso fora da faixa não é enviado; o erro da API aparece no campo', async () => {
    const { calls, user } = await openPets({
      'GET /api/customer/pets': () => json(200, []),
      'POST /api/customer/pets': () => problem(400, 'dados inválidos', { errors: { weightKg: 'de 0,5 a 120 kg, com no máximo uma casa decimal' } }),
    })
    await user.type(screen.getByLabelText('Nome do pet'), 'Thor')
    await user.selectOptions(screen.getByLabelText('Espécie'), 'DOG')
    await user.type(screen.getByLabelText('Peso em kg (opcional)'), '130')
    await user.click(screen.getByRole('button', { name: 'Cadastrar pet' }))
    expect(await screen.findByText('Informe o peso em kg, de 0,5 a 120 (por exemplo, 12,5).')).toBeInTheDocument()
    expect(calls.some((c) => c.method === 'POST' && c.path === '/api/customer/pets')).toBe(false)

    await user.clear(screen.getByLabelText('Peso em kg (opcional)'))
    await user.type(screen.getByLabelText('Peso em kg (opcional)'), '12,5')
    await user.click(screen.getByRole('button', { name: 'Cadastrar pet' }))
    expect(await screen.findByText('de 0,5 a 120 kg, com no máximo uma casa decimal')).toBeInTheDocument()
  })

  it('sem ficha, o corpo vai com campos vazios e a linha não mostra "Ficha"', async () => {
    const { calls, user } = await openPets({
      'GET /api/customer/pets': () => json(200, []),
      'POST /api/customer/pets': () => json(201, { ...THOR, coat: null, weightKg: null, temperament: null, healthNotes: null, vaccinated: null }),
    })
    await user.type(screen.getByLabelText('Nome do pet'), 'Thor')
    await user.selectOptions(screen.getByLabelText('Espécie'), 'DOG')
    await user.click(screen.getByRole('button', { name: 'Cadastrar pet' }))
    await screen.findByRole('button', { name: 'Cadastrar pet' })
    expect(calls.find((c) => c.method === 'POST' && c.path === '/api/customer/pets')?.body).toMatchObject({ coat: '', weightKg: null, temperament: '', healthNotes: '', vaccinated: null })
    expect(screen.queryByText(/^Ficha:/)).not.toBeInTheDocument()
  })

  it('editar traz a ficha preenchida (peso com vírgula)', async () => {
    const { user } = await openPets({ 'GET /api/customer/pets': () => json(200, [THOR]) })
    await user.click(await screen.findByRole('button', { name: /Editar/ }))
    expect(screen.getAllByLabelText('Peso em kg (opcional)').some((el) => (el as HTMLInputElement).value === '32,5')).toBe(true)
    expect(screen.getAllByLabelText('Pelo (opcional)').some((el) => (el as HTMLSelectElement).value === 'DOUBLE')).toBe(true)
    expect(screen.getAllByLabelText('Vacinas em dia (opcional)').some((el) => (el as HTMLSelectElement).value === 'yes')).toBe(true)
  })
})

describe('ficha do pet na agenda e na fila', () => {
  const today = todayIn(ZONE)
  const item = (pet: Record<string, unknown> | null) => ({
    id: 'a-1',
    startsAt: `${today}T14:00:00Z`,
    endsAt: `${today}T15:00:00Z`,
    status: 'SCHEDULED',
    serviceId: 's-1',
    serviceName: 'Banho',
    customerId: 'c-1',
    customerName: 'Marina Souza',
    pet,
  })

  it('a agenda do dia mostra o aviso de comportamento reativo, a ficha e a saúde; sem ficha não desenha nada', async () => {
    const { user } = await loginAs('PROVIDER', {
      [`GET /api/agenda?date=${today}`]: () =>
        json(200, [item({ name: 'Thor', species: 'DOG', breed: 'Golden', size: 'LARGE', notes: null, coat: 'DOUBLE', weightKg: 32.5, temperament: 'REACTIVE', healthNotes: 'Alergia a shampoo de aveia', vaccinated: true }), { ...item({ name: 'Mel', species: 'CAT' }), id: 'a-2' }]),
    })
    await user.click(within(screen.getByRole('navigation', { name: 'Menu do prestador' })).getByRole('link', { name: 'Agenda' }))
    expect(await screen.findByText('Atenção: pode reagir ou morder')).toBeInTheDocument()
    expect(screen.getByText('Pelo duplo · 32,5 kg · Vacinas em dia')).toBeInTheDocument()
    expect(screen.getByText('Saúde e alergias: Alergia a shampoo de aveia')).toBeInTheDocument()
    expect(screen.getAllByText('Atenção: pode reagir ou morder')).toHaveLength(1) // a Mel não tem ficha
  })

  it('a fila mostra o aviso e a ficha de quem chegou', async () => {
    const { user } = await loginAs('PROVIDER', {
      'GET /api/agenda/queue': () =>
        json(200, [{ ...item({ name: 'Thor', species: 'DOG', temperament: 'ANXIOUS', coat: 'LONG', weightKg: 8 }), position: 1, checkedInAt: `${today}T14:00:00Z` }]),
    })
    await user.click(within(screen.getByRole('navigation', { name: 'Menu do prestador' })).getByRole('link', { name: 'Fila' }))
    expect(await screen.findByText('Pelo longo · 8 kg · Ansioso')).toBeInTheDocument()
    expect(screen.queryByText('Atenção: pode reagir ou morder')).not.toBeInTheDocument()
  })
})
