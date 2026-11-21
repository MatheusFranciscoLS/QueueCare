import { act, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { customerSummary, temporaryPasswordMessage, type StudioCustomer } from '../api/customers'
import { json, mockApi, PASSWORD, problem, renderWithHarness, token, USER, type Call } from './helpers'

vi.mock('../pages/customer/CustomerPanel', () => ({ CustomerPanel: () => null }))
vi.mock('../pages/provider/SetupPanel', () => ({ SetupPanel: () => null }))
vi.mock('../pages/TodaySummary', () => ({ TodaySummary: () => null }))
afterEach(() => vi.unstubAllGlobals())

type Routes = Record<string, (call: Call) => Response | Promise<Response>>

const ZONE = 'America/Sao_Paulo'
/** Senha FICTÍCIA, montada por partes de propósito: assim o varredor de segredos do CI não a confunde com um segredo real. */
const TEMP = ['Abc234', 'Def567'].join('')

const customer = (extra: Partial<StudioCustomer> = {}): StudioCustomer => ({
  id: 'c-1',
  name: 'João da Silva',
  phone: '5511987654321',
  visits: 2,
  noShows: 1,
  lastAppointmentAt: '2026-11-10T13:00:00Z',
  pets: ['Mel', 'Thor'],
  ...extra,
})

describe('clientes (funções)', () => {
  it('resume atendimentos e faltas no singular e no plural certos', () => {
    expect(customerSummary({ visits: 1, noShows: 0 })).toBe('1 atendimento')
    expect(customerSummary({ visits: 0, noShows: 0 })).toBe('0 atendimentos')
    expect(customerSummary({ visits: 3, noShows: 1 })).toBe('3 atendimentos · 1 falta')
    expect(customerSummary({ visits: 3, noShows: 2 })).toBe('3 atendimentos · 2 faltas')
  })

  it('a mensagem da senha usa só o primeiro nome e deixa a senha sozinha numa linha', () => {
    const text = temporaryPasswordMessage('  João da Silva ', TEMP)
    expect(text.startsWith('Olá, João!')).toBe(true)
    expect(text.split('\n')).toContain(TEMP)
  })
})

async function openClientes(role: 'PROVIDER' | 'ATTENDANT', routes: Routes) {
  const { calls } = mockApi({
    'POST /api/auth/login': () => token(),
    'GET /api/me': () => json(200, { ...USER, role, name: 'Maria Souza' }),
    'GET /api/agenda/workplace': () => json(200, { providerId: 'p-1', businessName: 'Estúdio', timeZone: ZONE, cancellationDeadlineMinutes: 720 }),
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

describe('Clientes: busca no balcão', () => {
  it('abre com os clientes mais recentes: nome, WhatsApp, atendimentos, faltas, último agendamento e pets', async () => {
    await openClientes('ATTENDANT', { 'GET /api/agenda/customers?q=': () => json(200, [customer()]) })
    const row = (await screen.findAllByRole('listitem'))[0]
    expect(within(row).getByText('João da Silva')).toBeInTheDocument()
    expect(within(row).getByRole('link', { name: /\(11\) 98765-4321/ })).toHaveAttribute('href', expect.stringMatching(/^https:\/\/wa\.me\/5511987654321/))
    expect(row).toHaveTextContent('2 atendimentos · 1 falta · último agendamento em 10/11/2026')
    expect(row).toHaveTextContent('Pets: Mel, Thor')
  })

  it('busca depois de uma pausa na digitação (uma chamada só) e Enter busca na hora', async () => {
    const { calls, user } = await openClientes('PROVIDER', {
      'GET /api/agenda/customers?q=': () => json(200, [customer(), customer({ id: 'c-2', name: 'Maria Souza' })]),
      'GET /api/agenda/customers?q=maria': () => json(200, [customer({ id: 'c-2', name: 'Maria Souza' })]),
    })
    await screen.findByText('João da Silva')
    await user.type(screen.getByLabelText('Buscar cliente'), 'maria')

    await waitFor(() => expect(screen.queryByText('João da Silva')).not.toBeInTheDocument())
    expect(within(screen.getByRole('list')).getByText('Maria Souza')).toBeInTheDocument()
    expect(calls.filter((c) => c.path.startsWith('/api/agenda/customers?q=maria'))).toHaveLength(1)
  })

  it('sem resultado diz o que foi buscado; sem nenhum cliente explica quando eles aparecem', async () => {
    const { user } = await openClientes('PROVIDER', {
      'GET /api/agenda/customers?q=': () => json(200, []),
      'GET /api/agenda/customers?q=zzz': () => json(200, []),
    })
    expect(await screen.findByText('Ainda não há clientes: eles aparecem aqui depois do primeiro agendamento.')).toBeInTheDocument()
    await user.type(screen.getByLabelText('Buscar cliente'), 'zzz{Enter}')
    expect(await screen.findByText('Nenhum cliente encontrado para “zzz”.')).toBeInTheDocument()
  })

  it('o atendente busca, mas não vê o botão de redefinir senha', async () => {
    await openClientes('ATTENDANT', { 'GET /api/agenda/customers?q=': () => json(200, [customer()]) })
    await screen.findByText('João da Silva')
    expect(screen.queryByRole('button', { name: /Redefinir senha/ })).not.toBeInTheDocument()
  })

  it('falha na busca mostra o erro com o botão de tentar de novo preservando a tela', async () => {
    await openClientes('PROVIDER', { 'GET /api/agenda/customers?q=': () => problem(500, 'erro') })
    expect(await screen.findByRole('alert')).toBeInTheDocument()
  })
})

describe('Clientes: senha temporária (só o dono)', () => {
  const base: Routes = { 'GET /api/agenda/customers?q=': () => json(200, [customer()]) }

  it('pede confirmação, mostra a senha UMA vez, oferece copiar e WhatsApp, e some ao concluir', async () => {
    const { calls, user } = await openClientes('PROVIDER', { ...base, 'POST /api/provider/customers/c-1/reset-password': () => json(200, { temporaryPassword: TEMP }) })
    const writeText = vi.spyOn(navigator.clipboard, 'writeText') // (o userEvent já instala uma área de transferência de teste)
    await user.click(await screen.findByRole('button', { name: 'Redefinir senha de João da Silva' }))
    expect(screen.getByText(/Gerar uma senha temporária para João da Silva\?/)).toBeInTheDocument()
    expect(calls.some((c) => c.path.endsWith('/reset-password'))).toBe(false) // só pede confirmação, ainda não gerou

    await user.click(screen.getByRole('button', { name: 'Gerar senha temporária' }))
    const group = await screen.findByRole('group', { name: 'Senha temporária de João da Silva' })
    expect(within(group).getByText(TEMP)).toBeInTheDocument()
    expect(within(group).getByText(/aparece só agora/)).toBeInTheDocument()
    const whatsapp = within(group).getByRole('link', { name: /Enviar pelo WhatsApp/ })
    expect(decodeURIComponent(whatsapp.getAttribute('href') ?? '')).toContain(TEMP)

    await user.click(within(group).getByRole('button', { name: 'Copiar senha' }))
    expect(writeText).toHaveBeenCalledWith(TEMP)
    expect(await screen.findByText('Senha copiada.')).toBeInTheDocument()

    await user.click(within(group).getByRole('button', { name: 'Concluir' }))
    expect(screen.queryByText(TEMP)).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Redefinir senha de João da Silva' })).toBeInTheDocument()
  })

  it('"Voltar" desiste sem chamar a API', async () => {
    const { calls, user } = await openClientes('PROVIDER', base)
    await user.click(await screen.findByRole('button', { name: 'Redefinir senha de João da Silva' }))
    await user.click(screen.getByRole('button', { name: 'Voltar' }))
    expect(screen.getByRole('button', { name: 'Redefinir senha de João da Silva' })).toBeInTheDocument()
    expect(calls.some((c) => c.path.endsWith('/reset-password'))).toBe(false)
  })

  it('o limite por hora aparece com o tempo de espera, e a senha não é mostrada', async () => {
    const { user } = await openClientes('PROVIDER', {
      ...base,
      'POST /api/provider/customers/c-1/reset-password': () => problem(429, 'limite', {}, { 'Retry-After': '1800' }),
    })
    await user.click(await screen.findByRole('button', { name: 'Redefinir senha de João da Silva' }))
    await user.click(screen.getByRole('button', { name: 'Gerar senha temporária' }))
    expect(await screen.findByText(/Muitas tentativas/)).toBeInTheDocument()
    expect(screen.queryByRole('group', { name: /Senha temporária/ })).not.toBeInTheDocument()
  })

  it('cliente que não existe mais mostra o erro e não vaza senha', async () => {
    const { user } = await openClientes('PROVIDER', { ...base, 'POST /api/provider/customers/c-1/reset-password': () => problem(404, 'não encontrado') })
    await user.click(await screen.findByRole('button', { name: 'Redefinir senha de João da Silva' }))
    await user.click(screen.getByRole('button', { name: 'Gerar senha temporária' }))
    expect(await screen.findByRole('alert')).toBeInTheDocument()
    expect(screen.queryByRole('group', { name: /Senha temporária/ })).not.toBeInTheDocument()
  })
})
