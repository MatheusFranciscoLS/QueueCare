import { act, fireEvent, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { json, mockApi, PASSWORD, problem, renderWithHarness, token, USER, type Call } from './helpers'

// O painel do Início tem testes próprios; aqui ele sairia do caminho das contagens de chamadas.
vi.mock('../pages/customer/CustomerPanel', () => ({ CustomerPanel: () => null }))
vi.mock('../pages/provider/SetupPanel', () => ({ SetupPanel: () => null }))

afterEach(() => vi.unstubAllGlobals())

type Routes = Record<string, (call: Call) => Response | Promise<Response>>

const ZONE = 'America/Sao_Paulo'
const MINE = '/api/appointments/mine?page=0&size=50'
// 2030-12-10 13:00Z = 10:00 em São Paulo (UTC-3)
const item = (overrides: Record<string, unknown> = {}) => ({
  id: 'a-1',
  providerId: 'p-1',
  serviceId: 's-1',
  startsAt: '2030-12-10T13:00:00Z',
  endsAt: '2030-12-10T14:00:00Z',
  status: 'SCHEDULED',
  businessName: 'Estúdio Pelo e Ponto',
  timeZone: ZONE,
  serviceName: 'Banho e tosa',
  pet: { name: 'Rex', species: 'DOG' },
  ...overrides,
})
const SLOTS = '/api/providers/p-1/slots?serviceId=s-1&date=2030-12-10&excludeAppointmentId=a-1'
const slot = (hhmm: string) => ({ localStart: `2030-12-10T${hhmm}:00`, startsAt: `2030-12-10T${hhmm}:00Z`, endsAt: `2030-12-10T${hhmm}:00Z` })

function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((r) => (resolve = r))
  return { promise, resolve }
}

async function openMine(routes: Routes) {
  const { calls } = mockApi({
    'POST /api/auth/login': () => token(),
    'GET /api/me': () => json(200, { ...USER, role: 'CUSTOMER', name: 'Maria Souza' }),
    ...routes,
  })
  const view = renderWithHarness()
  await act(async () => view.api().login('x@exemplo.test', PASSWORD))
  await screen.findByRole('heading', { name: 'Olá, Maria Souza' })
  const user = userEvent.setup()
  await user.click(within(screen.getByRole('navigation', { name: 'Menu do cliente' })).getByRole('link', { name: 'Meus agendamentos' }))
  await screen.findByRole('heading', { name: 'Meus agendamentos' })
  return { calls, user }
}

const posts = (calls: Call[], suffix: string) => calls.filter((c) => c.method === 'POST' && c.path.endsWith(suffix))
const gets = (calls: Call[], path: string) => calls.filter((c) => c.method === 'GET' && c.path === path)

async function openBox(user: ReturnType<typeof userEvent.setup>) {
  await user.click(await screen.findByRole('button', { name: 'Remarcar' }))
  return screen.findByRole('region', { name: 'Remarcar agendamento' })
}

describe('remarcar: abrir e escolher', () => {
  it('só agendamento SCHEDULED com fuso tem o botão Remarcar', async () => {
    await openMine({
      [`GET ${MINE}`]: () => json(200, [item({ id: 'a-1' }), item({ id: 'a-2', status: 'COMPLETED' }), item({ id: 'a-3', status: 'CANCELLED' }), item({ id: 'a-4', timeZone: null })]),
    })
    await screen.findAllByRole('listitem')
    expect(screen.getAllByRole('button', { name: 'Remarcar' })).toHaveLength(1)
  })

  it('abre na data do agendamento, pede os horários IGNORANDO o próprio agendamento e só habilita confirmar depois da escolha', async () => {
    const { calls, user } = await openMine({ [`GET ${MINE}`]: () => json(200, [item()]), [`GET ${SLOTS}`]: () => json(200, [slot('09:30'), slot('10:30')]) })
    const box = await openBox(user)
    await within(box).findByRole('button', { name: '10:30' })
    expect(gets(calls, SLOTS)).toHaveLength(1)
    const confirm = within(box).getByRole('button', { name: 'Confirmar remarcação' })
    expect(confirm).toBeDisabled()

    await user.click(within(box).getByRole('button', { name: '10:30' }))

    expect(confirm).toBeEnabled()
    expect(within(box).getByText(/às/)).toHaveTextContent('às 10:30')
  })

  it('"Voltar" fecha a caixa sem enviar nada e devolve o foco para "Remarcar"', async () => {
    const { calls, user } = await openMine({ [`GET ${MINE}`]: () => json(200, [item()]), [`GET ${SLOTS}`]: () => json(200, [slot('10:30')]) })
    const box = await openBox(user)
    await user.click(within(box).getByRole('button', { name: 'Voltar' }))
    expect(screen.queryByRole('region', { name: 'Remarcar agendamento' })).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Remarcar' })).toHaveFocus()
    expect(posts(calls, '/reschedule')).toHaveLength(0)
  })
})

describe('remarcar: confirmar', () => {
  it('envia o horário LOCAL escolhido, mostra o agendamento movido mesmo se a lista seguinte falhar e fecha a caixa com foco na linha', async () => {
    let listCalls = 0
    const moved = item({ startsAt: '2030-12-10T13:30:00Z', endsAt: '2030-12-10T14:30:00Z' })
    const { calls, user } = await openMine({
      [`GET ${MINE}`]: () => (++listCalls === 1 ? json(200, [item()]) : problem(500, 'erro')),
      [`GET ${SLOTS}`]: () => json(200, [slot('10:30')]),
      'POST /api/appointments/a-1/reschedule': () => json(200, moved),
    })
    const box = await openBox(user)
    await user.click(await within(box).findByRole('button', { name: '10:30' }))

    await user.click(within(box).getByRole('button', { name: 'Confirmar remarcação' }))

    expect(await screen.findByText(/Agendamento remarcado: Banho e tosa em Estúdio Pelo e Ponto, terça-feira, 10 de dezembro de 2030, às 10:30\./)).toBeInTheDocument()
    expect(posts(calls, '/reschedule')[0].body).toEqual({ localStart: '2030-12-10T10:30:00' })
    expect(screen.queryByRole('region', { name: 'Remarcar agendamento' })).not.toBeInTheDocument()
    // a lista seguinte falhou: o agendamento movido (confirmado pela API) continua na tela, com o aviso de dados velhos
    await screen.findByText(/Não foi possível atualizar os dados/)
    expect(screen.getByRole('listitem')).toHaveTextContent('10:30–11:30')
    expect(screen.getByRole('listitem')).toHaveFocus()
  })

  it('quando a lista seguinte chega, vale o que ela trouxer (um cancelamento da equipe não é escondido pelo estado local)', async () => {
    let listCalls = 0
    const { user } = await openMine({
      [`GET ${MINE}`]: () => (++listCalls === 1 ? json(200, [item()]) : json(200, [item({ startsAt: '2030-12-10T13:30:00Z', endsAt: '2030-12-10T14:30:00Z', status: 'CANCELLED' })])),
      [`GET ${SLOTS}`]: () => json(200, [slot('10:30')]),
      'POST /api/appointments/a-1/reschedule': () => json(200, item({ startsAt: '2030-12-10T13:30:00Z', endsAt: '2030-12-10T14:30:00Z' })),
    })
    const box = await openBox(user)
    await user.click(await within(box).findByRole('button', { name: '10:30' }))
    await user.click(within(box).getByRole('button', { name: 'Confirmar remarcação' }))

    await waitFor(() => expect(screen.getByRole('listitem')).toHaveTextContent('Cancelado'))
  })

  it('horário tomado (409): mostra o motivo, mantém a caixa, descarta a escolha e refaz a lista de horários', async () => {
    let slotCalls = 0
    const { calls, user } = await openMine({
      [`GET ${MINE}`]: () => json(200, [item()]),
      [`GET ${SLOTS}`]: () => (++slotCalls === 1 ? json(200, [slot('10:30')]) : json(200, [slot('11:00')])),
      'POST /api/appointments/a-1/reschedule': () => problem(409, 'x', { reason: 'SLOT_TAKEN' }),
    })
    const box = await openBox(user)
    await user.click(await within(box).findByRole('button', { name: '10:30' }))
    await user.click(within(box).getByRole('button', { name: 'Confirmar remarcação' }))

    expect(await screen.findByText('Esse horário acabou de ser ocupado por outra pessoa. Escolha outro.')).toBeInTheDocument()
    expect(await within(screen.getByRole('region', { name: 'Remarcar agendamento' })).findByRole('button', { name: '11:00' })).toBeInTheDocument()
    expect(gets(calls, SLOTS)).toHaveLength(2)
    expect(within(screen.getByRole('region', { name: 'Remarcar agendamento' })).getByRole('button', { name: 'Confirmar remarcação' })).toBeDisabled()
  })

  it.each([
    ['TOO_LATE', 422, 'Passou do prazo para cancelar ou remarcar.', false],
    ['RESCHEDULE_LIMIT', 422, 'Você já usou as 3 remarcações deste agendamento. Cancele e agende de novo, ou fale com o estúdio.', false],
    ['CONCURRENT_UPDATE', 409, 'O agendamento acabou de ser alterado por outra pessoa. Atualize a agenda.', true],
    ['NOT_RESCHEDULABLE', 409, 'Esse agendamento já começou e não pode mais ser remarcado.', true],
  ])('recusa %s: fecha a caixa, mostra o motivo na linha%s', async (reason, status, message, reloads) => {
    const { calls, user } = await openMine({
      [`GET ${MINE}`]: () => json(200, [item()]),
      [`GET ${SLOTS}`]: () => json(200, [slot('10:30')]),
      'POST /api/appointments/a-1/reschedule': () => problem(status, 'x', { reason }),
    })
    const box = await openBox(user)
    await user.click(await within(box).findByRole('button', { name: '10:30' }))
    await user.click(within(box).getByRole('button', { name: 'Confirmar remarcação' }))

    expect(await screen.findByText(message)).toBeInTheDocument()
    expect(screen.queryByRole('region', { name: 'Remarcar agendamento' })).not.toBeInTheDocument()
    await waitFor(() => expect(gets(calls, `${MINE}`)).toHaveLength(reloads ? 2 : 1))
  })

  it('um segundo envio durante a remarcação não remarca duas vezes', async () => {
    const pending = deferred<Response>()
    const { calls, user } = await openMine({
      [`GET ${MINE}`]: () => json(200, [item()]),
      [`GET ${SLOTS}`]: () => json(200, [slot('10:30')]),
      'POST /api/appointments/a-1/reschedule': () => pending.promise,
    })
    const box = await openBox(user)
    await user.click(await within(box).findByRole('button', { name: '10:30' }))
    const confirm = within(box).getByRole('button', { name: 'Confirmar remarcação' })
    // dois cliques no MESMO instante, antes de a tela re-renderizar com o botão bloqueado: o segundo é ignorado
    act(() => {
      fireEvent.click(confirm)
      fireEvent.click(confirm)
    })

    const busy = await within(box).findByRole('button', { name: 'Remarcando…' })
    expect(busy).toBeDisabled()
    expect(within(box).getByRole('button', { name: '10:30' })).toBeDisabled() // as escolhas ficam bloqueadas durante o envio
    expect(posts(calls, '/reschedule')).toHaveLength(1)
    await act(async () => pending.resolve(json(200, item())))
  })
})

describe('histórico do agendamento', () => {
  const EVENTS = [
    { type: 'SCHEDULED', at: '2030-11-01T12:00:00Z', by: 'CUSTOMER' },
    { type: 'RESCHEDULED', at: '2030-11-02T12:00:00Z', by: 'STAFF', actorName: 'Beatriz da Equipe', previousStartsAt: '2030-12-10T13:00:00Z', newStartsAt: '2030-12-11T13:00:00Z' },
    { type: 'CANCELLED', at: '2030-11-03T12:00:00Z', by: 'CUSTOMER' },
  ]

  it('mostra os eventos em ordem, em português, sem nome de funcionário (mesmo que a API o enviasse)', async () => {
    const { user } = await openMine({ [`GET ${MINE}`]: () => json(200, [item()]), 'GET /api/appointments/a-1/events': () => json(200, EVENTS) })
    await user.click(await screen.findByRole('button', { name: 'Histórico' }))

    const group = await screen.findByRole('group', { name: 'Histórico do agendamento' })
    const lines = within(group).getAllByRole('listitem')
    expect(lines).toHaveLength(3)
    expect(lines[0]).toHaveTextContent('Agendado por você')
    expect(lines[1]).toHaveTextContent('Remarcado pelo estúdio: de terça-feira, 10 de dezembro de 2030, 10:00 para quarta-feira, 11 de dezembro de 2030, 10:00')
    expect(lines[2]).toHaveTextContent('Cancelado por você')
    expect(group).not.toHaveTextContent('Beatriz')
    await user.click(screen.getByRole('button', { name: 'Ocultar histórico' }))
    expect(screen.queryByRole('group', { name: 'Histórico do agendamento' })).not.toBeInTheDocument()
  })

  it('erro ao carregar o histórico mostra o motivo', async () => {
    const { user } = await openMine({ [`GET ${MINE}`]: () => json(200, [item()]), 'GET /api/appointments/a-1/events': () => problem(404, 'x', { reason: 'APPOINTMENT_NOT_FOUND' }) })
    await user.click(await screen.findByRole('button', { name: 'Histórico' }))
    expect(await screen.findByText('Agendamento não encontrado.')).toBeInTheDocument()
  })
})
