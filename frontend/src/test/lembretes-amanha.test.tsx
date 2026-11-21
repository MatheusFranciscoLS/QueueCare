import { act, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { addDays, todayIn } from '../api/dates'
import type { AgendaItem } from '../api/agenda'
import { tomorrowReminders } from '../api/summary'
import { json, mockApi, PASSWORD, problem, renderWithHarness, token, USER, type Call } from './helpers'

vi.mock('../pages/provider/SetupPanel', () => ({ SetupPanel: () => null }))
afterEach(() => vi.unstubAllGlobals())

type Routes = Record<string, (call: Call) => Response | Promise<Response>>

const ZONE = 'America/Sao_Paulo'
const today = todayIn(ZONE)
const tomorrow = addDays(today, 1)

const item = (id: string, extra: Partial<AgendaItem> = {}): AgendaItem => ({
  id,
  startsAt: `${tomorrow}T13:00:00Z`,
  endsAt: `${tomorrow}T14:00:00Z`,
  status: 'SCHEDULED',
  serviceId: 's-1',
  serviceName: 'Banho',
  customerId: 'c-1',
  customerName: 'Joana Lima',
  customerPhone: '5511912345678',
  remindedAt: null,
  ...extra,
})

describe('lembretes de amanhã (função)', () => {
  it('conta os agendados e quantos ainda podem ser lembrados (com telefone e sem lembrete)', () => {
    const items = [
      item('a'), // pode lembrar
      item('b', { remindedAt: '2026-11-20T20:00:00Z' }), // já lembrado
      item('c', { customerPhone: null }), // sem telefone: o app não consegue lembrar
      item('d', { status: 'CANCELLED' }), // não conta
      item('e', { status: 'CHECKED_IN' }), // não conta
    ]
    expect(tomorrowReminders(items)).toEqual({ scheduled: 3, pending: 1 })
    expect(tomorrowReminders([])).toEqual({ scheduled: 0, pending: 0 })
  })
})

async function homeOfStaff(tomorrowItems: AgendaItem[] | 'falha') {
  const routes: Routes = {
    'POST /api/auth/login': () => token(),
    'GET /api/me': () => json(200, { ...USER, role: 'PROVIDER', name: 'Helena Prado' }),
    'GET /api/agenda/workplace': () => json(200, { providerId: 'p-1', businessName: 'Estúdio Bem-te-vi', timeZone: ZONE, cancellationDeadlineMinutes: 720 }),
    ['GET /api/agenda?date=' + today]: () => json(200, []),
    'GET /api/agenda/queue': () => json(200, []),
    ['GET /api/agenda?date=' + tomorrow]: () => (tomorrowItems === 'falha' ? problem(500, 'erro') : json(200, tomorrowItems)),
  }
  const { calls } = mockApi(routes)
  const view = renderWithHarness()
  await act(async () => view.api().login('x@exemplo.test', PASSWORD))
  await screen.findByRole('heading', { name: 'Olá, Helena Prado' })
  return { calls, user: userEvent.setup() }
}

describe('Início do estúdio: lembretes de amanhã', () => {
  it('mostra quantos agendados e quantos ainda sem lembrete, com atalho para a agenda de amanhã', async () => {
    await homeOfStaff([item('a'), item('b', { customerName: 'Pedro Alves' }), item('c', { remindedAt: '2026-11-20T20:00:00Z' })])
    const line = await screen.findByText(/Amanhã:/)
    const paragraph = line.closest('p') as HTMLElement
    expect(paragraph).toHaveTextContent('Amanhã: 3 agendados; 2 sem lembrete.')
    expect(within(paragraph).getByRole('link', { name: 'Lembrar pelo WhatsApp' })).toHaveAttribute('href', `/agenda?data=${tomorrow}`)
  })

  it('tudo lembrado: só diz que está em dia, com atalho para ver', async () => {
    await homeOfStaff([item('a', { remindedAt: '2026-11-20T20:00:00Z' })])
    const paragraph = (await screen.findByText(/Amanhã:/)).closest('p') as HTMLElement
    expect(paragraph).toHaveTextContent('Amanhã: 1 agendado, todos já lembrados.')
    expect(within(paragraph).getByRole('link', { name: 'Ver amanhã' })).toHaveAttribute('href', `/agenda?data=${tomorrow}`)
  })

  it('amanhã sem agendamentos, ou sem conseguir ler, o resumo de hoje continua e nada é inventado', async () => {
    await homeOfStaff([])
    await screen.findByText(/Nenhum atendimento por vir hoje/)
    expect(screen.queryByText(/Amanhã:/)).not.toBeInTheDocument()
  })

  it('falha ao ler amanhã não derruba o resumo de hoje', async () => {
    await homeOfStaff('falha')
    expect(await screen.findByText(/Nenhum atendimento por vir hoje/)).toBeInTheDocument()
    expect(screen.queryByText(/Amanhã:/)).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Atualizar resumo' })).not.toBeInTheDocument()
  })

  it('o atalho abre a Agenda já no dia de amanhã', async () => {
    const { user, calls } = await homeOfStaff([item('a')])
    await user.click(await screen.findByRole('link', { name: 'Lembrar pelo WhatsApp' }))
    expect(await screen.findByRole('heading', { level: 1, name: 'Agenda' })).toBeInTheDocument()
    await screen.findByLabelText('Data')
    expect(screen.getByLabelText('Data')).toHaveValue(tomorrow)
    expect(calls.filter((c) => c.path === '/api/agenda?date=' + tomorrow).length).toBeGreaterThan(0)
  })
})
