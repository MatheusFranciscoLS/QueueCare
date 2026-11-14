import { act, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { addDays, todayIn } from '../api/dates'
import { json, mockApi, PASSWORD, problem, renderWithHarness, token, USER, type Call } from './helpers'

afterEach(() => vi.unstubAllGlobals())

type Routes = Record<string, (call: Call) => Response | Promise<Response>>

const WORKPLACE = { businessName: 'Oficina Central', timeZone: 'America/Sao_Paulo' }
const today = todayIn(WORKPLACE.timeZone)
const tomorrow = addDays(today, 1)
// 15:00Z = 12:00 em São Paulo
const item = (id: string, status: string, day = today, name = 'Maria Souza') => ({
  id,
  startsAt: `${day}T15:00:00Z`,
  endsAt: `${day}T16:00:00Z`,
  status,
  serviceId: 's-1',
  serviceName: 'Troca de óleo',
  customerId: 'c-1',
  customerName: name,
})
const queueItem = (id: string, position: number, name: string, checkedInAt: string) => ({
  position,
  id,
  startsAt: `${today}T15:00:00Z`,
  endsAt: `${today}T16:00:00Z`,
  serviceId: 's-1',
  serviceName: 'Troca de óleo',
  customerId: 'c-1',
  customerName: name,
  checkedInAt,
})

function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((r) => (resolve = r))
  return { promise, resolve }
}

async function loginAs(role: string, routes: Routes) {
  const { calls } = mockApi({
    'POST /api/auth/login': () => token(),
    'GET /api/me': () => json(200, { ...USER, role, name: 'Fulana Silva' }),
    'GET /api/agenda/workplace': () => json(200, WORKPLACE),
    ...routes,
  })
  const view = renderWithHarness()
  await act(async () => view.api().login('x@exemplo.test', PASSWORD))
  await screen.findByRole('heading', { name: 'Olá, Fulana Silva' })
  return { calls, view, user: userEvent.setup() }
}

const menu = (label: string) => within(screen.getByRole('navigation')).getByRole('link', { name: label })
const posts = (calls: Call[], suffix: string) => calls.filter((c) => c.method === 'POST' && c.path.endsWith(suffix))

describe('agenda: chegada e conclusão', () => {
  it('"Chegou" registra a chegada, mostra a nova situação e oferece "Concluir atendimento" (mesmo se o GET seguinte falhar)', async () => {
    let gets = 0
    const { calls, user } = await loginAs('ATTENDANT', {
      [`GET /api/agenda?date=${today}`]: () => (++gets === 1 ? json(200, [item('a-1', 'SCHEDULED')]) : problem(500, 'x')),
      'POST /api/appointments/a-1/check-in': () => json(200, { id: 'a-1', status: 'CHECKED_IN' }),
    })
    await user.click(menu('Agenda'))
    await user.click(await screen.findByRole('button', { name: 'Chegou' }))

    expect(await screen.findByText('Chegada de Maria Souza registrada.')).toBeInTheDocument()
    expect(screen.getByText('Chegou')).toHaveClass('badge')
    expect(screen.queryByRole('button', { name: 'Chegou' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Cancelar agendamento' })).not.toBeInTheDocument() // quem chegou não se cancela
    expect(screen.getByRole('button', { name: 'Concluir atendimento' })).toBeInTheDocument()
    expect(posts(calls, '/check-in')).toHaveLength(1)
  })

  it('"Concluir atendimento" conclui e tira as ações da linha', async () => {
    const { calls, user } = await loginAs('PROVIDER', {
      [`GET /api/agenda?date=${today}`]: () => json(200, [item('a-1', 'CHECKED_IN')]),
      'POST /api/appointments/a-1/complete': () => json(200, { id: 'a-1', status: 'COMPLETED' }),
    })
    await user.click(menu('Agenda'))
    await user.click(await screen.findByRole('button', { name: 'Concluir atendimento' }))

    expect(await screen.findByText('Atendimento de Maria Souza concluído.')).toBeInTheDocument()
    expect(screen.getByText('Concluído')).toHaveClass('badge')
    const row = screen.getByRole('listitem')
    expect(within(row).queryByRole('button')).not.toBeInTheDocument()
    expect(posts(calls, '/complete')).toHaveLength(1)
  })

  it('a chegada só é oferecida no dia de HOJE do negócio (outro dia: só cancelar)', async () => {
    const { user } = await loginAs('ATTENDANT', {
      [`GET /api/agenda?date=${today}`]: () => json(200, [item('a-1', 'SCHEDULED')]),
      [`GET /api/agenda?date=${tomorrow}`]: () => json(200, [item('a-2', 'SCHEDULED', tomorrow)]),
    })
    await user.click(menu('Agenda'))
    expect(await screen.findByRole('button', { name: 'Chegou' })).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Próximo dia' }))
    await screen.findByText('12:00–13:00')
    expect(screen.queryByRole('button', { name: 'Chegou' })).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Cancelar agendamento' })).toBeInTheDocument()
  })

  it('duplo clique em "Chegou" envia uma única chegada e bloqueia o botão durante o envio', async () => {
    const slow = deferred<Response>()
    const { calls, user } = await loginAs('ATTENDANT', {
      [`GET /api/agenda?date=${today}`]: () => json(200, [item('a-1', 'SCHEDULED')]),
      'POST /api/appointments/a-1/check-in': () => slow.promise,
    })
    await user.click(menu('Agenda'))
    await user.dblClick(await screen.findByRole('button', { name: 'Chegou' }))
    expect(screen.getByRole('button', { name: 'Enviando…' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Cancelar agendamento' })).toBeDisabled()
    await act(async () => slow.resolve(json(200, { id: 'a-1', status: 'CHECKED_IN' })))
    await screen.findByText('Chegada de Maria Souza registrada.')
    expect(posts(calls, '/check-in')).toHaveLength(1)
  })

  it('uma lista atualizada de ANTES da chegada não desfaz a chegada registrada aqui', async () => {
    const { user } = await loginAs('ATTENDANT', {
      [`GET /api/agenda?date=${today}`]: () => json(200, [item('a-1', 'SCHEDULED')]), // sempre "Agendado": lista antiga
      'POST /api/appointments/a-1/check-in': () => json(200, { id: 'a-1', status: 'CHECKED_IN' }),
    })
    await user.click(menu('Agenda'))
    await user.click(await screen.findByRole('button', { name: 'Chegou' }))
    await screen.findByText('Chegada de Maria Souza registrada.')
    await waitFor(() => expect(screen.getByRole('button', { name: 'Concluir atendimento' })).toBeInTheDocument())
    expect(screen.queryByRole('button', { name: 'Chegou' })).not.toBeInTheDocument()
  })

  it('o foco vai para a linha depois de registrar a chegada (o botão pressionado some)', async () => {
    const { user } = await loginAs('ATTENDANT', {
      [`GET /api/agenda?date=${today}`]: () => json(200, [item('a-1', 'SCHEDULED')]),
      'POST /api/appointments/a-1/check-in': () => json(200, { id: 'a-1', status: 'CHECKED_IN' }),
    })
    await user.click(menu('Agenda'))
    await user.click(await screen.findByRole('button', { name: 'Chegou' }))
    await screen.findByText('Chegada de Maria Souza registrada.')
    expect(screen.getByRole('listitem')).toHaveFocus()
  })

  it('a confirmação do cancelamento abre com o foco em "Voltar" e devolve o foco a "Cancelar agendamento"', async () => {
    const { user } = await loginAs('ATTENDANT', { [`GET /api/agenda?date=${today}`]: () => json(200, [item('a-1', 'SCHEDULED')]) })
    await user.click(menu('Agenda'))
    await user.click(await screen.findByRole('button', { name: 'Cancelar agendamento' }))
    expect(screen.getByRole('button', { name: 'Voltar' })).toHaveFocus()
    await user.click(screen.getByRole('button', { name: 'Voltar' }))
    expect(screen.getByRole('button', { name: 'Cancelar agendamento' })).toHaveFocus()
  })

  it('WRONG_DAY mostra o motivo e NÃO muda a linha nem refaz a lista', async () => {
    let gets = 0
    const { user } = await loginAs('ATTENDANT', {
      [`GET /api/agenda?date=${today}`]: () => (++gets, json(200, [item('a-1', 'SCHEDULED')])),
      'POST /api/appointments/a-1/check-in': () => problem(422, 'x', { reason: 'WRONG_DAY' }),
    })
    await user.click(menu('Agenda'))
    await user.click(await screen.findByRole('button', { name: 'Chegou' }))

    expect(await screen.findByText('A chegada só pode ser registrada no dia do agendamento.')).toBeInTheDocument()
    expect(screen.getByText('Agendado')).toBeInTheDocument()
    expect(gets).toBe(1)
  })

  it.each([
    ['INVALID_STATE', 'Esse agendamento já mudou de situação. Atualize a agenda.'],
    ['ALREADY_CANCELLED', 'Esse agendamento já foi cancelado.'],
    ['CONCURRENT_UPDATE', 'O agendamento acabou de ser alterado por outra pessoa. Atualize a agenda.'],
  ])('recusa %s mostra o motivo e refaz a lista: a linha passa a valer o que o servidor diz', async (reason, message) => {
    let gets = 0
    const { user } = await loginAs('ATTENDANT', {
      // outra pessoa já registrou a chegada: a segunda leitura mostra CHECKED_IN
      [`GET /api/agenda?date=${today}`]: () => json(200, [item('a-1', ++gets === 1 ? 'SCHEDULED' : 'CHECKED_IN')]),
      'POST /api/appointments/a-1/check-in': () => problem(409, 'x', { reason }),
    })
    await user.click(menu('Agenda'))
    await user.click(await screen.findByRole('button', { name: 'Chegou' }))

    expect(await screen.findByText(message)).toBeInTheDocument()
    await waitFor(() => expect(screen.getByRole('button', { name: 'Concluir atendimento' })).toBeInTheDocument())
    expect(gets).toBe(2)
  })

  it('ALREADY_CANCELLED: a lista refeita já não tem a linha, mas o motivo continua na tela (o erro não mora na linha)', async () => {
    let gets = 0
    const { user } = await loginAs('ATTENDANT', {
      [`GET /api/agenda?date=${today}`]: () => json(200, ++gets === 1 ? [item('a-1', 'SCHEDULED')] : []), // a agenda não traz cancelados
      'POST /api/appointments/a-1/check-in': () => problem(409, 'x', { reason: 'ALREADY_CANCELLED' }),
    })
    await user.click(menu('Agenda'))
    await user.click(await screen.findByRole('button', { name: 'Chegou' }))

    await waitFor(() => expect(screen.getByText('Nenhum agendamento neste dia.')).toBeInTheDocument())
    expect(screen.getByRole('alert')).toHaveTextContent('Esse agendamento já foi cancelado.')
  })

  it('"Concluir" recusado por INVALID_STATE (outro atendente concluiu) refaz a lista e a linha deixa de oferecer ações', async () => {
    let gets = 0
    const { user } = await loginAs('PROVIDER', {
      [`GET /api/agenda?date=${today}`]: () => json(200, [item('a-1', ++gets === 1 ? 'CHECKED_IN' : 'COMPLETED')]),
      'POST /api/appointments/a-1/complete': () => problem(409, 'x', { reason: 'INVALID_STATE' }),
    })
    await user.click(menu('Agenda'))
    await user.click(await screen.findByRole('button', { name: 'Concluir atendimento' }))

    expect(await screen.findByText('Esse agendamento já mudou de situação. Atualize a agenda.')).toBeInTheDocument()
    await waitFor(() => expect(screen.getByText('Concluído')).toBeInTheDocument())
    expect(screen.queryByRole('button', { name: 'Concluir atendimento' })).not.toBeInTheDocument()
  })
})

describe('fila', () => {
  const QUEUE = [queueItem('a-2', 1, 'Joana Lima', '2026-11-14T12:30:00Z'), queueItem('a-1', 2, 'Maria Souza', '2026-11-14T13:05:00Z')]

  it.each(['ATTENDANT', 'PROVIDER'])('%s vê a fila por ordem, com a hora de chegada no fuso do negócio', async (role) => {
    const { calls, user } = await loginAs(role, { 'GET /api/agenda/queue': () => json(200, QUEUE) })
    await user.click(menu('Fila'))

    const rows = await screen.findAllByRole('listitem')
    expect(rows).toHaveLength(2)
    expect(rows[0]).toHaveTextContent('1º · Joana Lima · Troca de óleo · chegou às 09:30') // 12:30Z = 09:30 em São Paulo
    expect(rows[1]).toHaveTextContent('2º · Maria Souza · Troca de óleo · chegou às 10:05')
    expect(screen.getByText(/Oficina Central · quem já chegou hoje e espera/)).toBeInTheDocument()
    expect(calls.some((c) => c.path === '/api/provider')).toBe(false)
  })

  it('fila vazia avisa; "Atualizar a fila" pede de novo e mostra quem chegou', async () => {
    let queue: typeof QUEUE = []
    const { calls, user } = await loginAs('ATTENDANT', { 'GET /api/agenda/queue': () => json(200, queue) })
    await user.click(menu('Fila'))
    expect(await screen.findByText('Ninguém na fila agora.')).toBeInTheDocument()

    queue = [QUEUE[0]]
    await user.click(screen.getByRole('button', { name: 'Atualizar a fila' }))
    expect(await screen.findByText(/Joana Lima/)).toBeInTheDocument()
    expect(calls.filter((c) => c.path === '/api/agenda/queue')).toHaveLength(2)
  })

  it('concluir tira a pessoa da fila na hora (mesmo se o GET seguinte falhar) e as posições são refeitas', async () => {
    let gets = 0
    const { calls, user } = await loginAs('ATTENDANT', {
      'GET /api/agenda/queue': () => (++gets === 1 ? json(200, QUEUE) : problem(500, 'x')),
      'POST /api/appointments/a-2/complete': () => json(200, { id: 'a-2', status: 'COMPLETED' }),
    })
    await user.click(menu('Fila'))
    const first = (await screen.findAllByRole('listitem'))[0]
    await user.click(within(first).getByRole('button', { name: 'Concluir atendimento' }))

    expect(await screen.findByText('Atendimento de Joana Lima concluído.')).toBeInTheDocument()
    const rows = screen.getAllByRole('listitem')
    expect(rows).toHaveLength(1)
    expect(rows[0]).toHaveTextContent('1º · Maria Souza')
    expect(posts(calls, '/complete')).toHaveLength(1)
  })

  it('duplo clique em "Concluir atendimento" envia uma única conclusão', async () => {
    const slow = deferred<Response>()
    const { calls, user } = await loginAs('ATTENDANT', {
      'GET /api/agenda/queue': () => json(200, [QUEUE[0]]),
      'POST /api/appointments/a-2/complete': () => slow.promise,
    })
    await user.click(menu('Fila'))
    await user.dblClick(await screen.findByRole('button', { name: 'Concluir atendimento' }))
    expect(screen.getByRole('button', { name: 'Enviando…' })).toBeDisabled()
    await act(async () => slow.resolve(json(200, { id: 'a-2', status: 'COMPLETED' })))
    await screen.findByText('Atendimento de Joana Lima concluído.')
    expect(posts(calls, '/complete')).toHaveLength(1)
  })

  it('INVALID_STATE (outra pessoa já concluiu) mostra o motivo e refaz a fila', async () => {
    let gets = 0
    const { user } = await loginAs('ATTENDANT', {
      'GET /api/agenda/queue': () => json(200, ++gets === 1 ? QUEUE : [QUEUE[1]]),
      'POST /api/appointments/a-2/complete': () => problem(409, 'x', { reason: 'INVALID_STATE' }),
    })
    await user.click(menu('Fila'))
    const first = (await screen.findAllByRole('listitem'))[0]
    await user.click(within(first).getByRole('button', { name: 'Concluir atendimento' }))

    expect(await screen.findByText('Esse agendamento já mudou de situação. Atualize a agenda.')).toBeInTheDocument()
    await waitFor(() => expect(screen.getAllByRole('listitem')).toHaveLength(1))
    expect(screen.queryByText(/Joana Lima/)).not.toBeInTheDocument()
    expect(gets).toBe(2)
  })

  it('falha ao carregar mostra o motivo; falha ao atualizar mantém a fila com aviso', async () => {
    let gets = 0
    const { user } = await loginAs('ATTENDANT', {
      'GET /api/agenda/queue': () => (++gets === 1 ? json(200, QUEUE) : problem(500, 'x')),
    })
    await user.click(menu('Fila'))
    await screen.findAllByRole('listitem')
    await user.click(screen.getByRole('button', { name: 'Atualizar a fila' }))
    await waitFor(() => expect(screen.getAllByRole('alert').length).toBeGreaterThan(0))
    expect(screen.getAllByRole('listitem')).toHaveLength(2)
  })

  it('atendente sem vínculo recebe uma explicação; prestador sem negócio, o convite', async () => {
    const attendant = await loginAs('ATTENDANT', { 'GET /api/agenda/workplace': () => problem(403, 'x') })
    await attendant.user.click(menu('Fila'))
    expect(await screen.findByText(/ainda não está vinculado a um prestador/)).toBeInTheDocument()
    expect(attendant.calls.some((c) => c.path === '/api/agenda/queue')).toBe(false)
  })

  it('prestador sem negócio vê o convite para cadastrar', async () => {
    const { user } = await loginAs('PROVIDER', { 'GET /api/agenda/workplace': () => problem(404, 'x', { reason: 'PROVIDER_NOT_CONFIGURED' }) })
    await user.click(menu('Fila'))
    expect(await within(screen.getByRole('main')).findByRole('link', { name: 'Meu negócio' })).toBeInTheDocument()
  })

  it.each(['CUSTOMER', 'ADMIN'])('%s não vê o menu Fila e é mandado de volta ao início ao tentar /fila, sem chamar a fila', async (role) => {
    const { calls, view } = await loginAs(role, {})
    expect(screen.queryByRole('link', { name: 'Fila' })).not.toBeInTheDocument()

    act(() => view.navigate()('/fila'))

    await waitFor(() => expect(screen.getByRole('heading', { name: 'Olá, Fulana Silva' })).toBeInTheDocument())
    expect(calls.some((c) => c.path.startsWith('/api/agenda'))).toBe(false)
  })
})
