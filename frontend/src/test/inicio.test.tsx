import { act, screen, within } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { todayIn } from '../api/dates'
import { json, mockApi, PASSWORD, problem, renderWithHarness, token, USER, type Call } from './helpers'

vi.mock('../pages/TodaySummary', () => ({ TodaySummary: () => null }))
afterEach(() => vi.unstubAllGlobals())

type Routes = Record<string, (call: Call) => Response | Promise<Response>>

const ZONE = 'America/Sao_Paulo'
const today = todayIn(ZONE)

async function home(role: string, routes: Routes, extraUser: Record<string, unknown> = {}) {
  mockApi({
    'POST /api/auth/login': () => token(),
    'GET /api/me': () => json(200, { ...USER, role, name: 'Maria Souza', phone: '5511912345678', ...extraUser }),
    ...routes,
  })
  const view = renderWithHarness()
  await act(async () => view.api().login('x@exemplo.test', PASSWORD))
  await screen.findByRole('heading', { name: 'Olá, Maria Souza' })
}

const mine = (id: string, status: string, extra: Record<string, unknown> = {}) => ({
  id,
  providerId: 'p-1',
  serviceId: 's-1',
  startsAt: `${today}T14:00:00Z`,
  endsAt: `${today}T15:00:00Z`,
  status,
  businessName: 'Estúdio Bem-te-vi',
  timeZone: ZONE,
  serviceName: 'Banho',
  priceCents: 7000,
  pet: { name: 'Thor', species: 'DOG' },
  ...extra,
})
const PET = { id: 'pet-1', name: 'Thor', species: 'DOG', breed: null, size: 'LARGE', notes: null, active: true }

describe('Início do tutor', () => {
  it('mostra o próximo agendamento com pet, serviço, estúdio, valor e situação', async () => {
    await home('CUSTOMER', {
      'GET /api/appointments/mine?page=0&size=50': () => json(200, [mine('a-2', 'SCHEDULED', { startsAt: `${today}T18:00:00Z`, serviceName: 'Tosa' }), mine('a-1', 'SCHEDULED')]),
      'GET /api/customer/pets': () => json(200, [PET]),
    })
    const card = (await screen.findByRole('heading', { name: 'Próximo agendamento' })).closest('div') as HTMLElement
    expect(card).toHaveTextContent('Banho · Thor · Estúdio Bem-te-vi · R$ 70,00') // o mais cedo
    expect(card).not.toHaveTextContent('Tosa')
    expect(within(card).getByText('Agendado')).toBeInTheDocument()
    expect(within(card).getByRole('link', { name: 'Ver ou remarcar' })).toHaveAttribute('href', '/meus-agendamentos')
    expect(within(card).getByRole('link', { name: 'Agendar outro horário' })).toHaveAttribute('href', '/agendar')
  })

  it('pet pronto para retirada vira o primeiro aviso da tela; em atendimento também aparece', async () => {
    await home('CUSTOMER', {
      'GET /api/appointments/mine?page=0&size=50': () => json(200, [mine('a-1', 'READY'), mine('a-2', 'IN_PROGRESS', { pet: { name: 'Mel', species: 'CAT' } })]),
      'GET /api/customer/pets': () => json(200, [PET]),
    })
    const alert = await screen.findByRole('status')
    expect(alert).toHaveTextContent('Thor está pronto para retirada em Estúdio Bem-te-vi')
    expect(screen.getByText('Mel está em atendimento')).toBeInTheDocument()
  })

  it('concluídos e cancelados não contam como próximo; sem horário marcado, convida a agendar', async () => {
    await home('CUSTOMER', {
      'GET /api/appointments/mine?page=0&size=50': () => json(200, [mine('a-1', 'COMPLETED'), mine('a-2', 'CANCELLED')]),
      'GET /api/customer/pets': () => json(200, [PET]),
    })
    expect(await screen.findByText('Você não tem nenhum horário marcado.')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Agendar um horário' })).toHaveAttribute('href', '/agendar')
  })

  it('sem pet: os três primeiros passos', async () => {
    await home('CUSTOMER', {
      'GET /api/appointments/mine?page=0&size=50': () => json(200, []),
      'GET /api/customer/pets': () => json(200, []),
    })
    expect(await screen.findByRole('heading', { name: 'Comece por aqui' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Cadastre o seu pet' })).toHaveAttribute('href', '/pets')
    expect(screen.getByRole('link', { name: 'Escolha o serviço e o horário' })).toHaveAttribute('href', '/agendar')
  })

  it('lista os pets ativos com a espécie', async () => {
    await home('CUSTOMER', {
      'GET /api/appointments/mine?page=0&size=50': () => json(200, []),
      'GET /api/customer/pets': () => json(200, [PET, { ...PET, id: 'pet-2', name: 'Mel', species: 'CAT' }]),
    })
    const section = (await screen.findByRole('heading', { name: 'Meus pets' })).closest('div') as HTMLElement
    expect(section).toHaveTextContent('ThorCachorro')
    expect(section).toHaveTextContent('MelGato')
  })

  it('se as consultas falharem o painel some e os atalhos continuam', async () => {
    await home('CUSTOMER', {
      'GET /api/appointments/mine?page=0&size=50': () => problem(500, 'x'),
      'GET /api/customer/pets': () => problem(500, 'x'),
    })
    expect(await screen.findByRole('heading', { name: 'No dia a dia' })).toBeInTheDocument()
    expect(within(screen.getByRole('main')).getByRole('link', { name: 'Agendar' })).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'Próximo agendamento' })).not.toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'Comece por aqui' })).not.toBeInTheDocument()
  })
})

describe('Início do estúdio: configuração', () => {
  const BUSINESS = { id: 'p-1', businessName: 'Estúdio', timeZone: ZONE, minimumNoticeMinutes: 0, cancellationDeadlineMinutes: 60 }
  const SERVICE = { id: 's-1', name: 'Banho', durationMinutes: 60, active: true }
  const WINDOW = { dayOfWeek: 'MONDAY', start: '08:00:00', end: '12:00:00' }

  it('tudo configurado: a seção de configuração não aparece', async () => {
    await home('PROVIDER', {
      'GET /api/provider': () => json(200, BUSINESS),
      'GET /api/provider/services': () => json(200, [SERVICE]),
      'GET /api/provider/availability': () => json(200, [WINDOW]),
    })
    expect(await screen.findByRole('heading', { name: 'No dia a dia' })).toBeInTheDocument()
    await act(async () => undefined)
    expect(screen.queryByRole('heading', { name: 'Configure o seu negócio' })).not.toBeInTheDocument()
  })

  it('falta serviço e expediente: marca o que já está pronto e mostra o resto', async () => {
    await home('PROVIDER', {
      'GET /api/provider': () => json(200, BUSINESS),
      'GET /api/provider/services': () => json(200, [{ ...SERVICE, active: false }]), // só desativado não conta
      'GET /api/provider/availability': () => json(200, []),
    })
    expect(await screen.findByRole('heading', { name: 'Configure o seu negócio' })).toBeInTheDocument()
    const items = screen.getAllByRole('listitem').filter((li) => li.closest('.setup'))
    expect(items).toHaveLength(3)
    expect(items[0]).toHaveTextContent('Pronto')
    expect(items[1]).not.toHaveTextContent('Pronto')
    expect(items[2]).not.toHaveTextContent('Pronto')
  })

  it('negócio ainda não cadastrado: os três passos pendentes', async () => {
    await home('PROVIDER', { 'GET /api/provider': () => problem(404, 'negócio não cadastrado', { reason: 'PROVIDER_NOT_CONFIGURED' }) })
    expect(await screen.findByRole('heading', { name: 'Configure o seu negócio' })).toBeInTheDocument()
    expect(screen.queryByText(/Pronto/)).not.toBeInTheDocument()
    expect(within(screen.getByRole('main')).getByRole('link', { name: 'Meu negócio' })).toHaveAttribute('href', '/meu-negocio')
  })

  it('erro ao consultar: mostra os três atalhos de sempre, sem marcar nada', async () => {
    await home('PROVIDER', { 'GET /api/provider': () => problem(500, 'x') })
    expect(await screen.findByRole('heading', { name: 'Configure o seu negócio' })).toBeInTheDocument()
    expect(within(screen.getByRole('main')).getByRole('link', { name: 'Serviços' })).toBeInTheDocument()
    expect(screen.queryByText(/Pronto/)).not.toBeInTheDocument()
  })
})
