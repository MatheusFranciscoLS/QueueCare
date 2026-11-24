import { act, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { counterBookingMessage, type StudioCustomer } from '../api/customers'
import { todayIn } from '../api/dates'
import { json, mockApi, PASSWORD, problem, renderWithHarness, token, USER, type Call } from './helpers'

vi.mock('../pages/customer/CustomerPanel', () => ({ CustomerPanel: () => null }))
vi.mock('../pages/provider/SetupPanel', () => ({ SetupPanel: () => null }))
vi.mock('../pages/TodaySummary', () => ({ TodaySummary: () => null }))
afterEach(() => vi.unstubAllGlobals())

type Routes = Record<string, (call: Call) => Response | Promise<Response>>

const ZONE = 'America/Sao_Paulo'
const today = todayIn(ZONE)
const SERVICE = { id: 's-1', name: 'Banho', durationMinutes: 60, coatExtraMinutes: 0 }
const PET = { id: 'pet-1', name: 'Bidu', species: 'DOG', size: 'MEDIUM', active: true }
const SLOT = { localStart: `${today}T09:00:00`, startsAt: `${today}T12:00:00Z`, endsAt: `${today}T13:00:00Z` }
const SLOTS_URL = `GET /api/agenda/customers/c-9/slots?serviceId=s-1&date=${today}&petId=pet-1`

const created = { id: 'c-9', name: 'Dona Marta', phone: '5511987654321', hasEmail: false }

const counterRow = (extra: Partial<StudioCustomer> = {}): StudioCustomer => ({
  id: 'c-9',
  name: 'Dona Marta',
  phone: '5511987654321',
  visits: 0,
  noShows: 0,
  lastAppointmentAt: null,
  pets: ['Bidu'],
  byCounter: true,
  hasEmail: false,
  ...extra,
})

describe('balcão (funções)', () => {
  it('a confirmação usa o primeiro nome, o estúdio, o pet e o horário, e só fala de sinal quando ele existe', () => {
    const base = { tutorName: '  Dona Marta ', studioName: 'Estúdio Bem-te-vi', petName: 'Bidu', when: 'terça-feira, 24 de novembro às 09:00' }
    expect(counterBookingMessage({ ...base, depositCents: null })).toBe(
      'Olá, Dona! Seu horário no Estúdio Bem-te-vi está marcado para terça-feira, 24 de novembro às 09:00, para Bidu. Se precisar mudar, é só avisar por aqui.',
    )
    expect(counterBookingMessage({ ...base, depositCents: 3000 })).toContain('sinal de R$ 30,00 por Pix')
  })
})

async function openClientes(role: 'PROVIDER' | 'ATTENDANT', routes: Routes) {
  const { calls } = mockApi({
    'POST /api/auth/login': () => token(),
    'GET /api/me': () => json(200, { ...USER, role, name: 'Maria Souza' }),
    'GET /api/agenda/workplace': () => json(200, { providerId: 'p-1', businessName: 'Estúdio Bem-te-vi', timeZone: ZONE, cancellationDeadlineMinutes: 720 }),
    ...routes,
  })
  const view = renderWithHarness()
  await act(async () => view.api().login('x@exemplo.test', PASSWORD))
  await screen.findByRole('heading', { name: 'Olá, Maria Souza' })
  const user = userEvent.setup()
  await user.click(within(screen.getByRole('navigation')).getByRole('link', { name: 'Clientes' }))
  await screen.findByRole('heading', { level: 1, name: 'Clientes' })
  return { calls, user }
}

async function fillNewCustomer(user: ReturnType<typeof userEvent.setup>, name = 'Dona Marta', phone = '(11) 98765-4321', email = '') {
  await user.click(await screen.findByRole('button', { name: 'Novo cliente' }))
  await user.type(screen.getByLabelText('Nome do tutor'), name)
  await user.type(screen.getByLabelText('WhatsApp do tutor'), phone)
  if (email) await user.type(screen.getByLabelText('E-mail (opcional)'), email)
}

/** Rotas de um cadastro que dá certo e de uma agenda com um horário livre. */
function happyRoutes(extra: Routes = {}): Routes {
  let pets: unknown[] = []
  return {
    'GET /api/agenda/customers?q=': () => json(200, []),
    'GET /api/agenda/customers?q=987654321': () => json(200, []),
    'POST /api/agenda/customers': () => json(201, created),
    'GET /api/agenda/services': () => json(200, [SERVICE]),
    'GET /api/agenda/customers/c-9/pets': () => json(200, pets),
    'POST /api/agenda/customers/c-9/pets': () => {
      pets = [PET]
      return json(201, PET)
    },
    [SLOTS_URL]: () => json(200, [SLOT]),
    ...extra,
  }
}

describe('Novo cliente pelo balcão', () => {
  it('valida nome, WhatsApp e e-mail antes de chamar a API, sem repetir o que foi digitado', async () => {
    const { calls, user } = await openClientes('ATTENDANT', happyRoutes())
    await user.click(await screen.findByRole('button', { name: 'Novo cliente' }))
    await user.click(screen.getByRole('button', { name: 'Cadastrar cliente' }))
    expect(screen.getByText('Informe o nome do tutor.')).toBeInTheDocument()
    expect(screen.getByText('Informe o WhatsApp do tutor, com DDD.')).toBeInTheDocument()

    await user.type(screen.getByLabelText('Nome do tutor'), 'Dona Marta')
    await user.type(screen.getByLabelText('WhatsApp do tutor'), '123')
    await user.type(screen.getByLabelText('E-mail (opcional)'), 'isso-nao-e-email')
    await user.click(screen.getByRole('button', { name: 'Cadastrar cliente' }))
    expect(screen.getByText(/Informe o DDD e o número/)).toBeInTheDocument()
    expect(screen.getByText(/Esse e-mail não parece válido/)).toBeInTheDocument()
    expect(calls.some((c) => c.method === 'POST' && c.path === '/api/agenda/customers')).toBe(false)
  })

  it('cadastra, manda o e-mail em branco como ausente e abre o agendamento para o tutor novo', async () => {
    const { calls, user } = await openClientes('ATTENDANT', happyRoutes())
    await fillNewCustomer(user)
    await user.click(screen.getByRole('button', { name: 'Cadastrar cliente' }))

    expect(await screen.findByRole('heading', { name: 'Agendar para Dona Marta' })).toBeInTheDocument()
    const post = calls.find((c) => c.method === 'POST' && c.path === '/api/agenda/customers')
    expect(post?.body).toEqual({ name: 'Dona Marta', phone: '(11) 98765-4321', email: null })
    expect(screen.getByText(/ainda não tem pet cadastrado/)).toBeInTheDocument()
  })

  it('antes de criar, mostra quem já tem o mesmo telefone; "Usar este cadastro" não duplica e "mesmo assim" cadastra', async () => {
    const existing: StudioCustomer = { id: 'c-1', name: 'Marta Antiga', phone: '5511987654321', visits: 3, noShows: 0, lastAppointmentAt: null, pets: ['Mel'] }
    const { calls, user } = await openClientes(
      'ATTENDANT',
      happyRoutes({
        'GET /api/agenda/customers?q=987654321': () => json(200, [existing]),
        'GET /api/agenda/customers?q=Marta%20Antiga': () => json(200, [existing]),
      }),
    )
    await fillNewCustomer(user)
    await user.click(screen.getByRole('button', { name: 'Cadastrar cliente' }))
    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('Já existe cadastro com este telefone')
    expect(alert).toHaveTextContent('Marta Antiga')
    expect(calls.some((c) => c.method === 'POST' && c.path === '/api/agenda/customers')).toBe(false)

    await user.click(within(alert).getByRole('button', { name: /Usar este cadastro/ }))
    expect(screen.queryByRole('heading', { name: 'Novo cliente' })).not.toBeInTheDocument()
    expect(screen.getByLabelText('Buscar cliente')).toHaveValue('Marta Antiga')

    await fillNewCustomer(user)
    await user.click(screen.getByRole('button', { name: 'Cadastrar cliente' }))
    await user.click(await screen.findByRole('button', { name: 'Cadastrar mesmo assim' }))
    expect(await screen.findByRole('heading', { name: 'Agendar para Dona Marta' })).toBeInTheDocument()
  })

  it('e-mail que já tem cadastro no app explica o que fazer, sem dizer de quem é', async () => {
    const { user } = await openClientes('ATTENDANT', happyRoutes({ 'POST /api/agenda/customers': () => problem(409, 'não foi possível criar a conta com os dados informados') }))
    await fillNewCustomer(user, 'Dona Marta', '(11) 98765-4321', 'marta@exemplo.test')
    await user.click(screen.getByRole('button', { name: 'Cadastrar cliente' }))
    expect(await screen.findByText(/Esse e-mail já tem cadastro no app/)).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Novo cliente' })).toBeInTheDocument()
  })
})

describe('Agendar pelo balcão', () => {
  it('cadastra o pet, escolhe serviço e horário, agenda e oferece a confirmação pelo WhatsApp', async () => {
    let bookedBody: unknown
    const booking = (call: Call) => {
      bookedBody = call.body
      return json(201, { id: 'a-1', startsAt: SLOT.startsAt, endsAt: SLOT.endsAt, status: 'SCHEDULED', priceCents: null, depositCents: null, depositStatus: 'NONE' })
    }
    const { calls, user } = await openClientes('ATTENDANT', happyRoutes({ 'POST /api/agenda/customers/c-9/appointments': booking }))
    await fillNewCustomer(user)
    await user.click(screen.getByRole('button', { name: 'Cadastrar cliente' }))
    await screen.findByRole('heading', { name: 'Agendar para Dona Marta' })

    // 1. pet
    await user.type(screen.getByLabelText('Nome do pet'), 'Bidu')
    await user.selectOptions(screen.getByLabelText('Espécie'), 'Cachorro')
    await user.click(screen.getByRole('button', { name: 'Cadastrar pet' }))
    const petSelect = await screen.findByLabelText('Pet')
    expect(petSelect).toHaveValue('pet-1')
    expect(calls.find((c) => c.method === 'POST' && c.path === '/api/agenda/customers/c-9/pets')?.body).toMatchObject({ name: 'Bidu', species: 'DOG' })

    // 2. serviço, 3. dia (hoje) e 4. horário
    await user.selectOptions(await screen.findByLabelText('Serviço'), 'Banho')
    const slots = await screen.findByRole('group', { name: 'Horários livres' })
    await user.click(within(slots).getByRole('button', { name: '09:00' }))
    await user.click(screen.getByRole('button', { name: 'Agendar às 09:00' }))

    expect(await screen.findByRole('heading', { name: 'Horário marcado' })).toBeInTheDocument()
    expect(bookedBody).toEqual({ serviceId: 's-1', localStart: SLOT.localStart, petId: 'pet-1' })
    expect(screen.getByRole('status')).toHaveTextContent('Banho de Bidu para Dona Marta')
    const link = screen.getByRole('link', { name: /Confirmar pelo WhatsApp/ })
    const href = decodeURIComponent(link.getAttribute('href') ?? '')
    expect(href).toContain('https://wa.me/5511987654321')
    expect(href).toContain('Olá, Dona! Seu horário no Estúdio Bem-te-vi está marcado')
    expect(href).toContain('para Bidu')

    await user.click(screen.getByRole('button', { name: 'Concluir' }))
    expect(screen.queryByRole('heading', { name: 'Horário marcado' })).not.toBeInTheDocument()
  })

  it('horário tomado no meio do caminho avisa, limpa a escolha e mantém a tela para escolher outro', async () => {
    const { user } = await openClientes(
      'ATTENDANT',
      happyRoutes({
        'GET /api/agenda/customers?q=': () => json(200, [counterRow()]),
        'GET /api/agenda/customers/c-9/pets': () => json(200, [PET]),
        'POST /api/agenda/customers/c-9/appointments': () => problem(409, 'o horário já está ocupado', { reason: 'SLOT_TAKEN' }),
      }),
    )
    await user.click(await screen.findByRole('button', { name: 'Agendar para Dona Marta' }))
    await screen.findByRole('heading', { name: 'Agendar para Dona Marta' })
    await user.selectOptions(await screen.findByLabelText('Pet'), 'Bidu (Cachorro · Médio)')
    await user.selectOptions(await screen.findByLabelText('Serviço'), 'Banho')
    await user.click(within(await screen.findByRole('group', { name: 'Horários livres' })).getByRole('button', { name: '09:00' }))
    await user.click(screen.getByRole('button', { name: 'Agendar às 09:00' }))

    expect(await screen.findByText('Esse horário acabou de ser ocupado. Escolha outro.')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Escolha um horário' })).toBeDisabled()
  })

  it('com sinal por Pix pedido, avisa a equipe e a mensagem ao tutor combina o sinal', async () => {
    const { user } = await openClientes(
      'ATTENDANT',
      happyRoutes({
        'GET /api/agenda/customers?q=': () => json(200, [counterRow()]),
        'GET /api/agenda/customers/c-9/pets': () => json(200, [PET]),
        'POST /api/agenda/customers/c-9/appointments': () => json(201, { id: 'a-1', startsAt: SLOT.startsAt, endsAt: SLOT.endsAt, status: 'SCHEDULED', depositCents: 3000, depositStatus: 'REQUESTED' }),
      }),
    )
    await user.click(await screen.findByRole('button', { name: 'Agendar para Dona Marta' }))
    await user.selectOptions(await screen.findByLabelText('Pet'), 'Bidu (Cachorro · Médio)')
    await user.selectOptions(await screen.findByLabelText('Serviço'), 'Banho')
    await user.click(within(await screen.findByRole('group', { name: 'Horários livres' })).getByRole('button', { name: '09:00' }))
    await user.click(screen.getByRole('button', { name: 'Agendar às 09:00' }))

    expect(await screen.findByText(/pede sinal de R\$ 30,00 por Pix/)).toBeInTheDocument()
    const href = decodeURIComponent(screen.getByRole('link', { name: /Confirmar pelo WhatsApp/ }).getAttribute('href') ?? '')
    expect(href).toContain('sinal de R$ 30,00 por Pix')
  })

  it('"Cancelar" fecha o painel sem agendar nada', async () => {
    const { calls, user } = await openClientes('ATTENDANT', happyRoutes({ 'GET /api/agenda/customers?q=': () => json(200, [counterRow()]), 'GET /api/agenda/customers/c-9/pets': () => json(200, [PET]) }))
    await user.click(await screen.findByRole('button', { name: 'Agendar para Dona Marta' }))
    await screen.findByRole('heading', { name: 'Agendar para Dona Marta' })
    await user.click(screen.getByRole('button', { name: 'Cancelar' }))
    await waitFor(() => expect(screen.queryByRole('heading', { name: 'Agendar para Dona Marta' })).not.toBeInTheDocument())
    expect(calls.some((c) => c.method === 'POST' && c.path.startsWith('/api/agenda'))).toBe(false)
  })
})

describe('Clientes do balcão na lista', () => {
  it('só quem foi cadastrado no balcão tem "Agendar"; sem e-mail avisa que não tem acesso e não oferece redefinir senha', async () => {
    const selfRegistered: StudioCustomer = { id: 'c-1', name: 'João da Silva', phone: '5511911112222', visits: 2, noShows: 0, lastAppointmentAt: null, pets: ['Thor'] }
    await openClientes('PROVIDER', { 'GET /api/agenda/customers?q=': () => json(200, [counterRow(), selfRegistered]) })
    const rows = await screen.findAllByRole('listitem')
    expect(within(rows[0]).getByRole('button', { name: 'Agendar para Dona Marta' })).toBeInTheDocument()
    expect(rows[0]).toHaveTextContent('Cadastrado no balcão · sem acesso ao app')
    expect(within(rows[0]).queryByRole('button', { name: /Redefinir senha/ })).not.toBeInTheDocument()

    expect(within(rows[1]).queryByRole('button', { name: /Agendar para/ })).not.toBeInTheDocument()
    expect(within(rows[1]).getByRole('button', { name: 'Redefinir senha de João da Silva' })).toBeInTheDocument()
  })

  it('cadastrado no balcão COM e-mail mostra "Cadastrado no balcão" sem dizer que falta acesso, e o dono pode redefinir a senha', async () => {
    await openClientes('PROVIDER', { 'GET /api/agenda/customers?q=': () => json(200, [counterRow({ hasEmail: true })]) })
    const row = (await screen.findAllByRole('listitem'))[0]
    expect(row).toHaveTextContent('Cadastrado no balcão')
    expect(row).not.toHaveTextContent('sem acesso ao app')
    expect(within(row).getByRole('button', { name: 'Redefinir senha de Dona Marta' })).toBeInTheDocument()
  })
})

describe('Direitos do tutor do balcão (só o dono)', () => {
  beforeEach(() => {
    URL.createObjectURL = vi.fn(() => 'blob:dados-do-tutor')
    URL.revokeObjectURL = vi.fn()
  })
  const base: Routes = { 'GET /api/agenda/customers?q=': () => json(200, [counterRow()]) }

  it('só o dono vê "Baixar dados" e "Excluir cadastro", e só de quem foi cadastrado no balcão', async () => {
    const selfRegistered: StudioCustomer = { id: 'c-1', name: 'João da Silva', phone: '5511911112222', visits: 2, noShows: 0, lastAppointmentAt: null, pets: ['Thor'] }
    await openClientes('PROVIDER', { 'GET /api/agenda/customers?q=': () => json(200, [counterRow(), selfRegistered]) })
    const rows = await screen.findAllByRole('listitem')
    expect(within(rows[0]).getByRole('button', { name: 'Baixar dados de Dona Marta' })).toBeInTheDocument()
    expect(within(rows[0]).getByRole('button', { name: 'Excluir cadastro de Dona Marta' })).toBeInTheDocument()
    expect(within(rows[1]).queryByRole('button', { name: /Baixar dados|Excluir cadastro/ })).not.toBeInTheDocument()
  })

  it('o atendente não vê as ações de privacidade', async () => {
    await openClientes('ATTENDANT', base)
    await screen.findByText('Dona Marta')
    expect(screen.queryByRole('button', { name: /Baixar dados|Excluir cadastro/ })).not.toBeInTheDocument()
  })

  it('baixa os dados do tutor como arquivo', async () => {
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => undefined)
    const { calls, user } = await openClientes('PROVIDER', { ...base, 'GET /api/provider/customers/c-9/export': () => json(200, { account: { name: 'Dona Marta' }, pets: [], appointments: [], waitlist: [] }) })
    await user.click(await screen.findByRole('button', { name: 'Baixar dados de Dona Marta' }))
    await waitFor(() => expect(click).toHaveBeenCalled())
    expect(calls.some((c) => c.path === '/api/provider/customers/c-9/export')).toBe(true)
    expect(URL.createObjectURL).toHaveBeenCalled()
    click.mockRestore()
  })

  it('excluir pede confirmação, avisa o que acontece, e "Voltar" desiste sem chamar a API', async () => {
    const { calls, user } = await openClientes('PROVIDER', base)
    await user.click(await screen.findByRole('button', { name: 'Excluir cadastro de Dona Marta' }))
    expect(screen.getByText(/Não dá para desfazer/)).toBeInTheDocument()
    expect(calls.some((c) => c.path.endsWith('/anonymize'))).toBe(false)
    await user.click(screen.getByRole('button', { name: 'Voltar' }))
    expect(screen.getByRole('button', { name: 'Excluir cadastro de Dona Marta' })).toBeInTheDocument()
    expect(calls.some((c) => c.path.endsWith('/anonymize'))).toBe(false)
  })

  it('confirmando, exclui e a lista é recarregada sem o cadastro', async () => {
    let removed = false
    const { calls, user } = await openClientes('PROVIDER', {
      'GET /api/agenda/customers?q=': () => json(200, removed ? [] : [counterRow()]),
      'POST /api/provider/customers/c-9/anonymize': () => {
        removed = true
        return new Response(null, { status: 204 })
      },
    })
    await user.click(await screen.findByRole('button', { name: 'Excluir cadastro de Dona Marta' }))
    await user.click(screen.getByRole('button', { name: 'Excluir cadastro' }))
    await waitFor(() => expect(screen.queryByText('Dona Marta')).not.toBeInTheDocument())
    expect(calls.some((c) => c.method === 'POST' && c.path === '/api/provider/customers/c-9/anonymize')).toBe(true)
  })

  it('tutor que já assumiu a conta: mostra o motivo e não exclui', async () => {
    const { user } = await openClientes('PROVIDER', { ...base, 'POST /api/provider/customers/c-9/anonymize': () => problem(409, 'o tutor já acessa o app', { reason: 'CUSTOMER_HAS_ACCESS' }) })
    await user.click(await screen.findByRole('button', { name: 'Excluir cadastro de Dona Marta' }))
    await user.click(screen.getByRole('button', { name: 'Excluir cadastro' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('ele mesmo exclui a conta em Meu perfil')
    expect(screen.getByText('Dona Marta')).toBeInTheDocument()
  })
})
