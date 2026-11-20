import { act, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { todayIn } from '../api/dates'
import { summarize } from '../api/summary'
import { json, mockApi, PASSWORD, problem, renderWithHarness, token, USER, type Call } from './helpers'

vi.mock('../pages/TodaySummary', () => ({ TodaySummary: () => null }))
afterEach(() => vi.unstubAllGlobals())

type Routes = Record<string, (call: Call) => Response | Promise<Response>>

const ZONE = 'America/Sao_Paulo'
const today = todayIn(ZONE)

const row = (id: string, status: string, extra: Record<string, unknown> = {}) => ({
  position: 1,
  status,
  id,
  startsAt: `${today}T14:00:00Z`,
  endsAt: `${today}T15:00:00Z`,
  serviceId: 's-1',
  serviceName: 'Banho',
  customerId: 'c-1',
  customerName: 'Marina Souza',
  customerPhone: '5511912345678',
  checkedInAt: `${today}T13:50:00Z`,
  pet: { name: 'Thor', species: 'DOG' },
  ...extra,
})

async function openQueue(routes: Routes) {
  const { calls } = mockApi({
    'POST /api/auth/login': () => token(),
    'GET /api/me': () => json(200, { ...USER, role: 'PROVIDER', name: 'Helena Prado' }),
    'GET /api/agenda/workplace': () => json(200, { providerId: 'p-1', businessName: 'Estúdio Bem-te-vi', timeZone: ZONE }),
    ...routes,
  })
  const view = renderWithHarness()
  await act(async () => view.api().login('x@exemplo.test', PASSWORD))
  await screen.findByRole('heading', { name: 'Olá, Helena Prado' })
  const user = userEvent.setup()
  await user.click(within(screen.getByRole('navigation', { name: 'Menu do prestador' })).getByRole('link', { name: 'Fila' }))
  await screen.findByRole('heading', { name: 'Fila' })
  return { calls, user }
}

describe('fila: passos do atendimento', () => {
  it('quem chegou pode começar; o aviso confirma e a fila traz a situação nova', async () => {
    let status = 'CHECKED_IN'
    const { calls, user } = await openQueue({
      'GET /api/agenda/queue': () => json(200, [row('a-1', status)]),
      'POST /api/appointments/a-1/start': () => {
        status = 'IN_PROGRESS'
        return json(200, { id: 'a-1', status })
      },
    })
    await user.click(await screen.findByRole('button', { name: 'Começar atendimento' }))
    expect(await screen.findByText('Atendimento de Thor (Marina Souza) iniciado.')).toBeInTheDocument()
    await waitFor(() => expect(screen.getByText('em atendimento')).toBeInTheDocument())
    expect(calls.filter((c) => c.path === '/api/appointments/a-1/start')).toHaveLength(1)
    expect(screen.queryByRole('button', { name: 'Começar atendimento' })).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Pronto para retirada' })).toBeInTheDocument()
  })

  it('em atendimento, "Pronto para retirada" avisa e a linha muda para "pronto"', async () => {
    let status = 'IN_PROGRESS'
    const { user } = await openQueue({
      'GET /api/agenda/queue': () => json(200, [row('a-1', status)]),
      'POST /api/appointments/a-1/ready': () => {
        status = 'READY'
        return json(200, { id: 'a-1', status })
      },
    })
    await user.click(await screen.findByRole('button', { name: 'Pronto para retirada' }))
    expect(await screen.findByText('Thor está pronto para retirada.')).toBeInTheDocument()
    await waitFor(() => expect(screen.getByText('pronto para retirada')).toBeInTheDocument())
  })

  it('pronto: oferece o WhatsApp com a mensagem pronta e conclui como "Pet retirado"', async () => {
    const { calls, user } = await openQueue({
      'GET /api/agenda/queue': () => json(200, [row('a-1', 'READY')]),
      'POST /api/appointments/a-1/complete': () => json(200, { id: 'a-1', status: 'COMPLETED' }),
    })
    const link = await screen.findByRole('link', { name: /Avisar no WhatsApp/ })
    const href = link.getAttribute('href') ?? ''
    expect(href).toContain('https://wa.me/5511912345678?text=')
    expect(decodeURIComponent(href)).toContain('Olá, Marina Souza! O Thor está pronto para retirada no Estúdio Bem-te-vi.')
    expect(link).toHaveAttribute('target', '_blank')
    expect(screen.queryByRole('button', { name: 'Começar atendimento' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Pronto para retirada' })).not.toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Pet retirado (concluir)' }))
    expect(await screen.findByText('Atendimento de Thor (Marina Souza) concluído.')).toBeInTheDocument()
    expect(calls.filter((c) => c.path === '/api/appointments/a-1/complete')).toHaveLength(1)
    await waitFor(() => expect(screen.queryByRole('button', { name: 'Pet retirado (concluir)' })).not.toBeInTheDocument())
  })

  it('sem telefone do tutor não há botão de WhatsApp', async () => {
    await openQueue({ 'GET /api/agenda/queue': () => json(200, [row('a-1', 'READY', { customerPhone: null })]) })
    await screen.findByRole('button', { name: 'Pet retirado (concluir)' })
    expect(screen.queryByRole('link', { name: /WhatsApp/ })).not.toBeInTheDocument()
  })

  it('o servidor recusou porque a situação mudou: mostra o motivo e refaz a fila', async () => {
    let gets = 0
    const { user } = await openQueue({
      'GET /api/agenda/queue': () => json(200, gets++ === 0 ? [row('a-1', 'CHECKED_IN')] : [row('a-1', 'IN_PROGRESS')]),
      'POST /api/appointments/a-1/start': () => problem(409, 'situação não permite', { reason: 'INVALID_STATE' }),
    })
    await user.click(await screen.findByRole('button', { name: 'Começar atendimento' }))
    expect(await screen.findByRole('alert')).toBeInTheDocument()
    await waitFor(() => expect(screen.getByText('em atendimento')).toBeInTheDocument())
  })

  it('cada linha mostra a própria situação, na ordem de chegada', async () => {
    await openQueue({
      'GET /api/agenda/queue': () =>
        json(200, [
          row('a-1', 'IN_PROGRESS', { position: 1, customerName: 'Ana' }),
          row('a-2', 'READY', { position: 2, customerName: 'Bia', checkedInAt: `${today}T13:55:00Z` }),
          row('a-3', 'CHECKED_IN', { position: 3, customerName: 'Caio', checkedInAt: `${today}T14:00:00Z` }),
        ]),
    })
    const rows = await screen.findAllByRole('listitem')
    expect(rows[0]).toHaveTextContent('1º')
    expect(rows[0]).toHaveTextContent('em atendimento')
    expect(rows[1]).toHaveTextContent('pronto para retirada')
    expect(rows[2]).toHaveTextContent('chegou às')
  })
})

describe('estados nas outras telas', () => {
  it('a agenda do dia mostra a situação em texto e oferece concluir; o resumo conta quem está em atendimento', async () => {
    const item = (id: string, status: string) => ({ id, startsAt: `${today}T14:00:00Z`, endsAt: `${today}T15:00:00Z`, status, serviceId: 's-1', serviceName: 'Banho', customerId: 'c', customerName: `Cliente ${id}` })
    const { user } = await (async () => {
      mockApi({
        'POST /api/auth/login': () => token(),
        'GET /api/me': () => json(200, { ...USER, role: 'PROVIDER', name: 'Helena Prado' }),
        'GET /api/agenda/workplace': () => json(200, { providerId: 'p-1', businessName: 'Estúdio', timeZone: ZONE }),
        [`GET /api/agenda?date=${today}`]: () => json(200, [item('a', 'IN_PROGRESS'), item('b', 'READY'), item('c', 'CHECKED_IN'), item('d', 'COMPLETED'), item('e', 'SCHEDULED')]),
      })
      const view = renderWithHarness()
      await act(async () => view.api().login('x@exemplo.test', PASSWORD))
      await screen.findByRole('heading', { name: 'Olá, Helena Prado' })
      return { user: userEvent.setup() }
    })()
    await user.click(within(screen.getByRole('navigation', { name: 'Menu do prestador' })).getByRole('link', { name: 'Agenda' }))
    expect(await screen.findByText('Em atendimento')).toBeInTheDocument()
    expect(screen.getByText('Pronto para retirada')).toBeInTheDocument()
    expect(screen.getAllByRole('button', { name: 'Concluir atendimento' })).toHaveLength(3) // chegou, em atendimento e pronto
    expect(screen.getByText('2 em atendimento')).toBeInTheDocument()
    expect(screen.getByText('1 esperando')).toBeInTheDocument()
  })

  it('Meus agendamentos mostra "Em atendimento" e "Pronto para retirada" ao tutor', async () => {
    const mine = (id: string, status: string) => ({ id, providerId: 'p-1', serviceId: 's-1', startsAt: `${today}T14:00:00Z`, endsAt: `${today}T15:00:00Z`, status, businessName: 'Estúdio', timeZone: ZONE, serviceName: 'Banho', pet: { name: 'Thor', species: 'DOG' } })
    mockApi({
      'POST /api/auth/login': () => token(),
      'GET /api/me': () => json(200, { ...USER, role: 'CUSTOMER', name: 'Maria Souza', phone: '5511912345678' }),
      'GET /api/appointments/mine?page=0&size=50': () => json(200, [mine('a', 'READY'), mine('b', 'IN_PROGRESS')]),
    })
    const view = renderWithHarness()
    await act(async () => view.api().login('x@exemplo.test', PASSWORD))
    await screen.findByRole('heading', { name: 'Olá, Maria Souza' })
    const user = userEvent.setup()
    await user.click(within(screen.getByRole('navigation', { name: 'Menu do cliente' })).getByRole('link', { name: 'Meus agendamentos' }))
    expect(await screen.findByText('Pronto para retirada')).toBeInTheDocument()
    expect(screen.getByText('Em atendimento')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Cancelar agendamento' })).not.toBeInTheDocument() // quem já chegou não se cancela
  })

  it('o resumo conta quem está no estúdio (chegou, em atendimento ou pronto) e escolhe o próximo', () => {
    const at = (status: string, h: number) => ({ id: status, status, startsAt: `${today}T${String(h).padStart(2, '0')}:00:00Z`, endsAt: `${today}T${String(h + 1).padStart(2, '0')}:00:00Z`, serviceId: 's', serviceName: 'Banho', customerId: 'c', customerName: 'C' })
    const result = summarize([at('CHECKED_IN', 10), at('IN_PROGRESS', 11), at('READY', 12), at('COMPLETED', 8), at('SCHEDULED', 13)], new Date(`${today}T09:00:00Z`))
    expect(result.checkedIn).toBe(3)
    expect(result.completed).toBe(1)
    expect(result.scheduled).toBe(1)
    expect(result.next?.status).toBe('CHECKED_IN')
  })
})
