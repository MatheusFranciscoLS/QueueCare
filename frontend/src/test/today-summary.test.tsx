import { act, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { todayIn } from '../api/dates'
import type { AgendaItem } from '../api/agenda'
import { summarize } from '../api/summary'
import { json, mockApi, PASSWORD, problem, renderWithHarness, token, USER, type Call } from './helpers'

afterEach(() => vi.unstubAllGlobals())

type Routes = Record<string, (call: Call) => Response | Promise<Response>>
const WORKPLACE = { businessName: 'Oficina Central', timeZone: 'America/Sao_Paulo' }
const today = todayIn(WORKPLACE.timeZone)

function item(id: string, status: string, startsAt: string, endsAt: string, customerName = 'Maria Souza'): AgendaItem {
  return { id, startsAt, endsAt, status, serviceId: 's-1', serviceName: 'Troca de óleo', customerId: 'c-1', customerName }
}

async function homeAs(role: string, routes: Routes) {
  mockApi({
    'POST /api/auth/login': () => token(),
    'GET /api/me': () => json(200, { ...USER, role, name: 'Fulana Silva' }),
    ...routes,
  })
  const view = renderWithHarness()
  await act(async () => view.api().login('x@exemplo.test', PASSWORD))
  await screen.findByRole('heading', { name: 'Olá, Fulana Silva' })
  return userEvent.setup()
}

describe('summarize', () => {
  const now = new Date('2026-11-14T15:00:00Z')
  const items = [
    item('a', 'COMPLETED', '2026-11-14T12:00:00Z', '2026-11-14T13:00:00Z'),
    item('b', 'CHECKED_IN', '2026-11-14T14:30:00Z', '2026-11-14T15:30:00Z', 'João'),
    item('c', 'SCHEDULED', '2026-11-14T16:00:00Z', '2026-11-14T17:00:00Z', 'Ana'),
    item('d', 'SCHEDULED', '2026-11-14T10:00:00Z', '2026-11-14T11:00:00Z'),
    item('e', 'CANCELLED', '2026-11-14T15:10:00Z', '2026-11-14T15:40:00Z'),
  ]

  it('conta por situação e ignora cancelados', () => {
    const s = summarize(items, now)
    expect([s.scheduled, s.checkedIn, s.completed]).toEqual([2, 1, 1])
  })

  it('o próximo é o primeiro ainda por vir (quem já passou do horário e não terminou conta), em ordem de horário', () => {
    expect(summarize(items, now).next?.id).toBe('b')
    expect(summarize(items, new Date('2026-11-14T15:45:00Z')).next?.id).toBe('c')
    expect(summarize(items, new Date('2026-11-14T18:00:00Z')).next).toBeUndefined()
  })
})

describe('resumo do dia na tela inicial', () => {
  it('PROVIDER vê agendados, fila e concluídos de hoje e o próximo atendimento', async () => {
    const future = new Date(Date.now() + 2 * 3600_000)
    await homeAs('PROVIDER', {
      'GET /api/agenda/workplace': () => json(200, WORKPLACE),
      [`GET /api/agenda?date=${today}`]: () =>
        json(200, [
          item('a', 'COMPLETED', '2020-01-01T10:00:00Z', '2020-01-01T11:00:00Z'),
          item('b', 'SCHEDULED', future.toISOString(), new Date(future.getTime() + 1800_000).toISOString(), 'João Pereira'),
        ]),
      'GET /api/agenda/queue': () => json(200, [{ position: 1, id: 'q-1', startsAt: '', endsAt: '', serviceId: 's', serviceName: 'x', customerId: 'c', customerName: 'Y', checkedInAt: '2026-11-14T12:00:00Z' }]),
    })
    const summary = await screen.findByRole('region', { name: /Hoje,/ })
    expect(within(summary).getByText('Agendados').nextSibling).toHaveTextContent('1')
    expect(within(summary).getByText('Na fila').nextSibling).toHaveTextContent('1')
    expect(within(summary).getByText('Concluídos').nextSibling).toHaveTextContent('1')
    expect(summary).toHaveTextContent('Próximo:')
    expect(summary).toHaveTextContent('João Pereira')
  })

  it('CUSTOMER não vê resumo e não faz nenhuma chamada de agenda', async () => {
    mockApi({ 'POST /api/auth/login': () => token(), 'GET /api/me': () => json(200, { ...USER, name: 'Fulana Silva' }) })
    const view = renderWithHarness()
    await act(async () => view.api().login('x@exemplo.test', PASSWORD))
    await screen.findByRole('heading', { name: 'Olá, Fulana Silva' })
    expect(screen.queryByRole('region', { name: /Hoje,/ })).not.toBeInTheDocument()
  })

  it('atendente sem vínculo (403): o resumo some e os atalhos continuam', async () => {
    await homeAs('ATTENDANT', { 'GET /api/agenda/workplace': () => problem(403, 'sem vínculo', { reason: 'NOT_LINKED' }) })
    expect(screen.queryByRole('region', { name: /Hoje,/ })).not.toBeInTheDocument()
    expect(screen.queryByText(/Não foi possível carregar o resumo/)).not.toBeInTheDocument()
    expect(within(screen.getByRole('main')).getByRole('link', { name: 'Agenda' })).toBeInTheDocument()
  })

  it('falha ao carregar avisa, mantém os atalhos e "Atualizar resumo" tenta de novo', async () => {
    let tries = 0
    const user = await homeAs('PROVIDER', {
      'GET /api/agenda/workplace': () => (++tries === 1 ? problem(500, 'x') : json(200, WORKPLACE)),
      [`GET /api/agenda?date=${today}`]: () => json(200, []),
      'GET /api/agenda/queue': () => json(200, []),
    })
    expect(await screen.findByText(/Não foi possível carregar o resumo de hoje/)).toBeInTheDocument()
    expect(within(screen.getByRole('main')).getByRole('link', { name: 'Meu negócio' })).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Atualizar resumo' }))
    expect(await screen.findByRole('region', { name: /Hoje,/ })).toHaveTextContent('Nenhum atendimento por vir hoje.')
  })


  it('ATTENDANT vinculado também vê o resumo', async () => {
    await homeAs('ATTENDANT', {
      'GET /api/agenda/workplace': () => json(200, WORKPLACE),
      [`GET /api/agenda?date=${today}`]: () => json(200, [item('a', 'SCHEDULED', '2099-01-01T10:00:00Z', '2099-01-01T11:00:00Z')]),
      'GET /api/agenda/queue': () => json(200, []),
    })
    const summary = await screen.findByRole('region', { name: /Hoje,/ })
    expect(within(summary).getByText('Agendados').nextSibling).toHaveTextContent('1')
  })

  it('com a tela aberta, quando o dia do negócio vira (fuso do negócio, não do aparelho) o resumo é refeito para o dia novo', async () => {
    vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval', 'Date'], shouldAdvanceTime: true })
    try {
      vi.setSystemTime(new Date('2026-11-15T01:59:30Z')) // 22:59:30 de sábado 14/11 em São Paulo
      const asked: string[] = []
      const day = (date: string) => () => {
        asked.push(date)
        return json(200, [])
      }
      await homeAs('PROVIDER', {
        'GET /api/agenda/workplace': () => json(200, WORKPLACE),
        'GET /api/agenda?date=2026-11-14': day('2026-11-14'),
        'GET /api/agenda?date=2026-11-15': day('2026-11-15'),
        'GET /api/agenda/queue': () => json(200, []),
      })
      expect(await screen.findByRole('region', { name: /Hoje, sábado, 14/ })).toBeInTheDocument()
      expect(asked).toEqual(['2026-11-14'])

      await act(async () => {
        vi.setSystemTime(new Date('2026-11-15T03:01:00Z')) // já é domingo 15/11 às 00:01 em São Paulo
        vi.advanceTimersByTime(60_000)
      })
      expect(await screen.findByRole('region', { name: /Hoje, domingo, 15/ })).toBeInTheDocument()
      expect(asked).toEqual(['2026-11-14', '2026-11-15'])
    } finally {
      vi.useRealTimers()
    }
  })
})
