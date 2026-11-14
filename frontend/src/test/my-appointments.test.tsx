import { act, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { json, mockApi, PASSWORD, problem, renderWithHarness, token, USER, type Call } from './helpers'

afterEach(() => vi.unstubAllGlobals())

type Routes = Record<string, (call: Call) => Response | Promise<Response>>

const ZONE = 'America/Sao_Paulo'
const MINE = '/api/appointments/mine?page=0&size=50'
// 2026-12-10 13:00Z = 10:00 em São Paulo (UTC-3)
const item = (id: string, status: string, startsAt = '2026-12-10T13:00:00Z', endsAt = '2026-12-10T14:00:00Z') => ({
  id,
  providerId: 'p-1',
  serviceId: 's-1',
  startsAt,
  endsAt,
  status,
  businessName: 'Oficina Central',
  timeZone: ZONE,
  serviceName: 'Troca de óleo',
})

function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((r) => (resolve = r))
  return { promise, resolve }
}

async function openMine(routes: Routes, role = 'CUSTOMER') {
  const { calls } = mockApi({
    'POST /api/auth/login': () => token(),
    'GET /api/me': () => json(200, { ...USER, role, name: 'Maria Souza' }),
    ...routes,
  })
  const view = renderWithHarness()
  await act(async () => view.api().login('x@exemplo.test', PASSWORD))
  await screen.findByRole('heading', { name: 'Olá, Maria Souza' })
  const user = userEvent.setup()
  return { calls, user, view }
}

async function goToMine(user: ReturnType<typeof userEvent.setup>) {
  await user.click(within(screen.getByRole('navigation', { name: 'Menu do cliente' })).getByRole('link', { name: 'Meus agendamentos' }))
}

describe('meus agendamentos: lista', () => {
  it('mostra data e hora no fuso do negócio, serviço, negócio e situação', async () => {
    const { user } = await openMine({ [`GET ${MINE}`]: () => json(200, [item('a-1', 'SCHEDULED'), item('a-2', 'CANCELLED', '2026-12-09T13:00:00Z', '2026-12-09T14:00:00Z')]) })
    await goToMine(user)
    const rows = await screen.findAllByRole('listitem')
    expect(rows).toHaveLength(2)
    expect(rows[0]).toHaveTextContent('quinta-feira, 10 de dezembro de 2026, 10:00–11:00 · Troca de óleo em Oficina Central')
    expect(within(rows[0]).getByText('Agendado')).toBeInTheDocument()
    expect(within(rows[1]).getByText('Cancelado')).toBeInTheDocument()
    expect(within(rows[1]).queryByRole('button')).not.toBeInTheDocument()
  })

  it('a data é a do NEGÓCIO: 02:00Z ainda é o dia anterior em São Paulo', async () => {
    const { user } = await openMine({ [`GET ${MINE}`]: () => json(200, [item('a-1', 'COMPLETED', '2026-12-10T02:00:00Z', '2026-12-10T03:00:00Z')]) })
    await goToMine(user)
    expect(await screen.findByRole('listitem')).toHaveTextContent('quarta-feira, 09 de dezembro de 2026, 23:00–00:00')
  })

  it('sem agendamentos: aviso com atalho para agendar', async () => {
    const { user } = await openMine({ [`GET ${MINE}`]: () => json(200, []) })
    await goToMine(user)
    expect(await screen.findByText(/Você ainda não tem agendamentos/)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Agendar um horário' })).toHaveAttribute('href', '/agendar')
  })

  it('falha ao carregar mostra o motivo, sem lista', async () => {
    const { user } = await openMine({ [`GET ${MINE}`]: () => problem(500, 'x') })
    await goToMine(user)
    expect(await screen.findByRole('alert')).toHaveTextContent('Algo deu errado no servidor')
  })

  it('perfis que não são cliente não veem o menu nem chamam a lista', async () => {
    for (const role of ['PROVIDER', 'ATTENDANT', 'ADMIN']) {
      const { calls } = await openMine({}, role)
      expect(screen.queryByRole('link', { name: 'Meus agendamentos' })).not.toBeInTheDocument()
      expect(calls.some((c) => c.path.startsWith('/api/appointments'))).toBe(false)
      vi.unstubAllGlobals()
      document.body.innerHTML = ''
    }
  })
})

describe('meus agendamentos: cancelar', () => {
  it('pede confirmação; "Voltar" não envia nada', async () => {
    const { calls, user } = await openMine({ [`GET ${MINE}`]: () => json(200, [item('a-1', 'SCHEDULED')]) })
    await goToMine(user)
    await user.click(await screen.findByRole('button', { name: 'Cancelar agendamento' }))
    expect(screen.getByText('Cancelar Troca de óleo em Oficina Central?')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Voltar' }))
    expect(screen.getByRole('button', { name: 'Cancelar agendamento' })).toBeInTheDocument()
    expect(calls.some((c) => c.path.endsWith('/cancel'))).toBe(false)
  })

  it('cancela, mostra "Cancelado" na hora (mesmo que o GET seguinte falhe) e avisa', async () => {
    let n = 0
    const { calls, user } = await openMine({
      [`GET ${MINE}`]: () => (++n === 1 ? json(200, [item('a-1', 'SCHEDULED')]) : problem(500, 'x')),
      'POST /api/appointments/a-1/cancel': () => json(200, item('a-1', 'CANCELLED')),
    })
    await goToMine(user)
    await user.click(await screen.findByRole('button', { name: 'Cancelar agendamento' }))
    await user.click(screen.getByRole('button', { name: 'Confirmar cancelamento' }))

    expect(await screen.findByText('Cancelado')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Cancelar agendamento' })).not.toBeInTheDocument()
    expect(screen.getByText('Agendamento cancelado: Troca de óleo em Oficina Central.')).toBeInTheDocument()
    expect(calls.filter((c) => c.path.endsWith('/cancel'))).toHaveLength(1)
  })

  it('duplo clique em confirmar envia um único cancelamento', async () => {
    const slow = deferred<Response>()
    const { calls, user } = await openMine({
      [`GET ${MINE}`]: () => json(200, [item('a-1', 'SCHEDULED')]),
      'POST /api/appointments/a-1/cancel': () => slow.promise,
    })
    await goToMine(user)
    await user.click(await screen.findByRole('button', { name: 'Cancelar agendamento' }))
    const confirm = screen.getByRole('button', { name: 'Confirmar cancelamento' })
    await user.dblClick(confirm)
    expect(screen.getByRole('button', { name: 'Cancelando…' })).toBeDisabled()
    await act(async () => slow.resolve(json(200, item('a-1', 'CANCELLED'))))
    await waitFor(() => expect(screen.getByText('Cancelado')).toBeInTheDocument())
    expect(calls.filter((c) => c.path.endsWith('/cancel'))).toHaveLength(1)
  })

  it.each([
    ['TOO_LATE', 422, 'Passou do prazo para cancelar.', false],
    ['ALREADY_CANCELLED', 409, 'Esse agendamento já foi cancelado.', true],
    ['NOT_CANCELLABLE', 409, 'Esse agendamento não pode mais ser cancelado.', true],
    ['CONCURRENT_UPDATE', 409, 'O agendamento acabou de ser alterado por outra pessoa. Atualize a agenda.', true],
  ])('recusa %s mostra o motivo e %s', async (reason, status, message, refetches) => {
    let gets = 0
    const { user } = await openMine({
      [`GET ${MINE}`]: () => json(200, [item('a-1', ++gets === 1 ? 'SCHEDULED' : 'CANCELLED')]),
      'POST /api/appointments/a-1/cancel': () => problem(status, 'x', { reason }),
    })
    await goToMine(user)
    await user.click(await screen.findByRole('button', { name: 'Cancelar agendamento' }))
    await user.click(screen.getByRole('button', { name: 'Confirmar cancelamento' }))

    expect(await screen.findByText(message)).toBeInTheDocument()
    if (refetches) {
      // o servidor já mudou: a lista é refeita e passa a mostrar a situação real
      await waitFor(() => expect(screen.getByText('Cancelado')).toBeInTheDocument())
      expect(gets).toBe(2)
    } else {
      // prazo vencido: nada mudou, o agendamento segue agendado
      expect(gets).toBe(1)
      expect(screen.getByText('Agendado')).toBeInTheDocument()
    }
    expect(screen.queryByRole('button', { name: 'Confirmar cancelamento' })).not.toBeInTheDocument()
  })
})
