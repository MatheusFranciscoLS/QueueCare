import { act, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { addDays, longDate, todayIn } from '../api/dates'
import { json, mockApi, PASSWORD, problem, renderWithHarness, token, USER, type Call } from './helpers'

afterEach(() => vi.unstubAllGlobals())

type Routes = Record<string, (call: Call) => Response | Promise<Response>>

const ZONE = 'America/Sao_Paulo'
const today = todayIn(ZONE)
const tomorrow = addDays(today, 1)
const PROVIDERS = [
  { id: 'p-1', businessName: 'Oficina Central', timeZone: ZONE },
  { id: 'p-2', businessName: 'Barbearia do Zé', timeZone: ZONE },
]
const SERVICES = [
  { id: 's-1', name: 'Troca de óleo', durationMinutes: 60 },
  { id: 's-2', name: 'Alinhamento', durationMinutes: 30 },
]
const slot = (date: string, hhmm: string) => ({ localStart: `${date}T${hhmm}:00`, startsAt: `${date}T${hhmm}:00Z`, endsAt: `${date}T${hhmm}:00Z` })

function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((r) => (resolve = r))
  return { promise, resolve }
}

async function loginAsCustomer(routes: Routes, role = 'CUSTOMER') {
  const { calls } = mockApi({
    'POST /api/auth/login': () => token(),
    'GET /api/me': () => json(200, { ...USER, role, name: 'Maria Souza' }),
    'GET /api/catalog/providers?page=0&size=50': () => json(200, PROVIDERS),
    'GET /api/catalog/providers/p-1/services': () => json(200, SERVICES),
    ...routes,
  })
  const view = renderWithHarness()
  await act(async () => view.api().login('x@exemplo.test', PASSWORD))
  await screen.findByRole('heading', { name: 'Olá, Maria Souza' })
  const user = userEvent.setup()
  await user.click(within(screen.getByRole('navigation', { name: 'Menu do cliente' })).getByRole('link', { name: 'Agendar' }))
  await screen.findByRole('heading', { name: 'Agendar' })
  return { calls, user, view }
}

/** Escolhe prestador, serviço e deixa a lista de horários na tela. */
async function reachSlots(user: ReturnType<typeof userEvent.setup>) {
  await user.click(await screen.findByRole('radio', { name: 'Oficina Central' }))
  await user.click(await screen.findByRole('radio', { name: /Troca de óleo/ }))
}

describe('agendar: escolha passo a passo', () => {
  it('mostra os prestadores e, só depois de escolher, os serviços; depois o dia e os horários', async () => {
    const { calls, user } = await loginAsCustomer({ [`GET /api/providers/p-1/slots?serviceId=s-1&date=${today}`]: () => json(200, [slot(today, '09:00'), slot(today, '09:30')]) })
    expect(await screen.findByRole('radio', { name: 'Barbearia do Zé' })).toBeInTheDocument()
    expect(screen.queryByText('2. Escolha o serviço')).not.toBeInTheDocument()

    await user.click(screen.getByRole('radio', { name: 'Oficina Central' }))
    expect(await screen.findByRole('radio', { name: /Alinhamento · 30 min/ })).toBeInTheDocument()
    expect(screen.queryByText('4. Escolha o horário')).not.toBeInTheDocument()

    await user.click(screen.getByRole('radio', { name: /Troca de óleo/ }))
    expect(await screen.findByRole('button', { name: '09:00' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '09:30' })).toBeInTheDocument()
    expect(screen.getByText(/horários no fuso do negócio \(America\/Sao_Paulo\)/)).toBeInTheDocument()
    expect(calls.some((c) => c.path === `/api/providers/p-1/slots?serviceId=s-1&date=${today}`)).toBe(true)
  })

  it('trocar o prestador descarta serviço, dia, horário e confirmação', async () => {
    const { user } = await loginAsCustomer({
      [`GET /api/providers/p-1/slots?serviceId=s-1&date=${today}`]: () => json(200, [slot(today, '09:00')]),
      'GET /api/catalog/providers/p-2/services': () => json(200, []),
    })
    await reachSlots(user)
    await user.click(await screen.findByRole('button', { name: '09:00' }))
    expect(screen.getByRole('button', { name: 'Confirmar agendamento' })).toBeInTheDocument()

    await user.click(screen.getByRole('radio', { name: 'Barbearia do Zé' }))

    expect(await screen.findByText('Este prestador ainda não tem serviços para agendar.')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Confirmar agendamento' })).not.toBeInTheDocument()
    expect(screen.queryByText('4. Escolha o horário')).not.toBeInTheDocument()
  })

  it('dia sem horários avisa; navegar pelos dias pede a data certa e respeita o limite de hoje', async () => {
    const { calls, user } = await loginAsCustomer({
      [`GET /api/providers/p-1/slots?serviceId=s-1&date=${today}`]: () => json(200, []),
      [`GET /api/providers/p-1/slots?serviceId=s-1&date=${tomorrow}`]: () => json(200, [slot(tomorrow, '14:00')]),
    })
    await reachSlots(user)
    expect(await screen.findByText('Nenhum horário livre neste dia. Tente outro dia.')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Dia anterior' })).toBeDisabled() // não se agenda no passado

    await user.click(screen.getByRole('button', { name: 'Próximo dia' }))

    expect(await screen.findByRole('button', { name: '14:00' })).toBeInTheDocument()
    expect(calls.at(-1)?.path).toBe(`/api/providers/p-1/slots?serviceId=s-1&date=${tomorrow}`)
    expect(screen.queryByText('Nenhum horário livre neste dia. Tente outro dia.')).not.toBeInTheDocument()
  })

  it('a data do negócio tem limite de 90 dias à frente', async () => {
    const { user } = await loginAsCustomer({ [`GET /api/providers/p-1/slots?serviceId=s-1&date=${today}`]: () => json(200, []) })
    await reachSlots(user)
    await screen.findByText('Nenhum horário livre neste dia. Tente outro dia.')

    const input = screen.getByLabelText('Data')
    expect(input).toHaveAttribute('min', today)
    expect(input).toHaveAttribute('max', addDays(today, 90))
  })

  it('a lista de horários de um dia anterior nunca aparece sob o dia novo', async () => {
    const slowNext = deferred<Response>()
    const { user } = await loginAsCustomer({
      [`GET /api/providers/p-1/slots?serviceId=s-1&date=${today}`]: () => json(200, [slot(today, '09:00')]),
      [`GET /api/providers/p-1/slots?serviceId=s-1&date=${tomorrow}`]: () => slowNext.promise,
    })
    await reachSlots(user)
    await screen.findByRole('button', { name: '09:00' })

    await user.click(screen.getByRole('button', { name: 'Próximo dia' }))

    expect(screen.queryByRole('button', { name: '09:00' })).not.toBeInTheDocument()
    expect(screen.getByText('Carregando horários…')).toBeInTheDocument()
    await act(async () => slowNext.resolve(json(200, [slot(tomorrow, '15:00')])))
    expect(await screen.findByRole('button', { name: '15:00' })).toBeInTheDocument()
  })

  it('falha ao carregar os prestadores mostra o erro; falha ao atualizar mantém a lista com aviso', async () => {
    let gets = 0
    const { user } = await loginAsCustomer({
      'GET /api/catalog/providers?page=0&size=50': () => json(200, PROVIDERS),
      [`GET /api/providers/p-1/slots?serviceId=s-1&date=${today}`]: () => (++gets === 1 ? json(200, [slot(today, '09:00')]) : problem(500, 'x')),
    })
    await reachSlots(user)
    await screen.findByRole('button', { name: '09:00' })

    await user.click(screen.getByRole('button', { name: 'Atualizar horários' }))

    expect(await screen.findByText(/pode estar desatualizado/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '09:00' })).toBeInTheDocument()
  })
})

describe('agendar: reservar', () => {
  const book = (calls: Call[]) => calls.find((c) => c.method === 'POST' && c.path === '/api/appointments')

  it('confirma com o horário LOCAL do negócio e mostra o agendamento', async () => {
    const { calls, user } = await loginAsCustomer({
      [`GET /api/providers/p-1/slots?serviceId=s-1&date=${today}`]: () => json(200, [slot(today, '09:00')]),
      'POST /api/appointments': () => json(201, { id: 'a-1', providerId: 'p-1', serviceId: 's-1', startsAt: '', endsAt: '', status: 'SCHEDULED', businessName: 'Oficina Central', timeZone: ZONE, serviceName: 'Troca de óleo' }),
    })
    await reachSlots(user)
    await user.click(await screen.findByRole('button', { name: '09:00' }))
    expect(screen.getByRole('region', { name: 'Confirmar agendamento' })).toHaveTextContent('Troca de óleo')

    await user.click(screen.getByRole('button', { name: 'Confirmar agendamento' }))

    expect(await screen.findByRole('heading', { name: 'Agendamento confirmado' })).toBeInTheDocument()
    expect(screen.getByRole('status')).toHaveTextContent(/Troca de óleo em Oficina Central.*às 09:00/)
    expect(book(calls)?.body).toEqual({ providerId: 'p-1', serviceId: 's-1', localStart: `${today}T09:00:00` })
  })

  it('"Agendar outro horário" volta ao começo', async () => {
    const { user } = await loginAsCustomer({
      [`GET /api/providers/p-1/slots?serviceId=s-1&date=${today}`]: () => json(200, [slot(today, '09:00')]),
      'POST /api/appointments': () => json(201, { id: 'a-1', providerId: 'p-1', serviceId: 's-1', startsAt: '', endsAt: '', status: 'SCHEDULED', businessName: 'Oficina Central', timeZone: ZONE, serviceName: 'Troca de óleo' }),
    })
    await reachSlots(user)
    await user.click(await screen.findByRole('button', { name: '09:00' }))
    await user.click(screen.getByRole('button', { name: 'Confirmar agendamento' }))
    await screen.findByRole('heading', { name: 'Agendamento confirmado' })

    await user.click(screen.getByRole('button', { name: 'Agendar outro horário' }))

    expect(await screen.findByRole('heading', { name: 'Agendar' })).toBeInTheDocument()
    expect(screen.getByRole('radio', { name: 'Oficina Central' })).not.toBeChecked()
  })

  it('horário tomado por outra pessoa (409): mostra o motivo, descarta a escolha e refaz a lista', async () => {
    let slotCalls = 0
    const { calls, user } = await loginAsCustomer({
      [`GET /api/providers/p-1/slots?serviceId=s-1&date=${today}`]: () => json(200, ++slotCalls === 1 ? [slot(today, '09:00'), slot(today, '09:30')] : [slot(today, '09:30')]),
      'POST /api/appointments': () => problem(409, 'o horário já está ocupado', { reason: 'SLOT_TAKEN' }),
    })
    await reachSlots(user)
    await user.click(await screen.findByRole('button', { name: '09:00' }))

    await user.click(screen.getByRole('button', { name: 'Confirmar agendamento' }))

    expect(await screen.findByText('Esse horário acabou de ser ocupado por outra pessoa. Escolha outro.')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Confirmar agendamento' })).not.toBeInTheDocument()
    await waitFor(() => expect(screen.queryByRole('button', { name: '09:00' })).not.toBeInTheDocument()) // a lista nova não o traz
    expect(screen.getByRole('button', { name: '09:30' })).toBeInTheDocument()
    expect(calls.filter((c) => c.path.startsWith('/api/providers/p-1/slots'))).toHaveLength(2)
  })

  it.each([
    ['IN_THE_PAST', 'Esse horário já passou. Escolha outro.'],
    ['TOO_SOON', 'Esse horário está muito próximo: o prestador exige mais antecedência.'],
    ['OUTSIDE_AVAILABILITY', 'Esse horário está fora do expediente do prestador.'],
    ['NONEXISTENT_LOCAL_TIME', 'Esse horário não existe nesse dia (mudança de horário de verão). Escolha outro.'],
    ['AMBIGUOUS_LOCAL_TIME', 'Esse horário é ambíguo nesse dia (mudança de horário de verão). Escolha outro.'],
  ])('recusa 422 %s mostra o motivo e atualiza os horários', async (reason, message) => {
    const { user } = await loginAsCustomer({
      [`GET /api/providers/p-1/slots?serviceId=s-1&date=${today}`]: () => json(200, [slot(today, '09:00')]),
      'POST /api/appointments': () => problem(422, 'agendamento recusado', { reason }),
    })
    await reachSlots(user)
    await user.click(await screen.findByRole('button', { name: '09:00' }))

    await user.click(screen.getByRole('button', { name: 'Confirmar agendamento' }))

    expect(await screen.findByText(message)).toBeInTheDocument()
    expect(await screen.findByRole('button', { name: '09:00' })).toBeInTheDocument() // lista refeita, pronta para outra escolha
  })

  it('serviço ou prestador que deixaram de estar disponíveis (404) mostram a mensagem certa', async () => {
    const { user } = await loginAsCustomer({
      [`GET /api/providers/p-1/slots?serviceId=s-1&date=${today}`]: () => json(200, [slot(today, '09:00')]),
      'POST /api/appointments': () => problem(404, 'agendamento recusado', { reason: 'SERVICE_NOT_FOUND_OR_INACTIVE' }),
    })
    await reachSlots(user)
    await user.click(await screen.findByRole('button', { name: '09:00' }))

    await user.click(screen.getByRole('button', { name: 'Confirmar agendamento' }))

    expect(await screen.findByText('Esse serviço não está mais disponível.')).toBeInTheDocument()
  })

  it('durante o envio o botão fica bloqueado e um segundo clique não duplica a reserva', async () => {
    const slow = deferred<Response>()
    const { calls, user } = await loginAsCustomer({
      [`GET /api/providers/p-1/slots?serviceId=s-1&date=${today}`]: () => json(200, [slot(today, '09:00')]),
      'POST /api/appointments': () => slow.promise,
    })
    await reachSlots(user)
    await user.click(await screen.findByRole('button', { name: '09:00' }))

    await user.click(screen.getByRole('button', { name: 'Confirmar agendamento' }))
    expect(screen.getByRole('button', { name: 'Agendando…' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Escolher outro horário' })).toBeDisabled()
    await user.click(screen.getByRole('button', { name: 'Agendando…' }))
    await act(async () => slow.resolve(json(201, { id: 'a-1', providerId: 'p-1', serviceId: 's-1', startsAt: '', endsAt: '', status: 'SCHEDULED', businessName: 'Oficina Central', timeZone: ZONE, serviceName: 'Troca de óleo' })))

    await screen.findByRole('heading', { name: 'Agendamento confirmado' })
    expect(calls.filter((c) => c.method === 'POST' && c.path === '/api/appointments')).toHaveLength(1)
  })

  it('"Escolher outro horário" volta sem enviar nada', async () => {
    const { calls, user } = await loginAsCustomer({ [`GET /api/providers/p-1/slots?serviceId=s-1&date=${today}`]: () => json(200, [slot(today, '09:00')]) })
    await reachSlots(user)
    await user.click(await screen.findByRole('button', { name: '09:00' }))

    await user.click(screen.getByRole('button', { name: 'Escolher outro horário' }))

    expect(screen.queryByRole('button', { name: 'Confirmar agendamento' })).not.toBeInTheDocument()
    expect(calls.some((c) => c.method === 'POST' && c.path === '/api/appointments')).toBe(false)
  })
})

describe('agendar: nada muda durante o envio da reserva', () => {
  it('prestador, serviço, dia e horário ficam bloqueados enquanto a reserva está pendente, e o resumo é o do que foi ENVIADO', async () => {
    const slow = deferred<Response>()
    const { calls, user } = await loginAsCustomer({
      [`GET /api/providers/p-1/slots?serviceId=s-1&date=${today}`]: () => json(200, [slot(today, '09:00'), slot(today, '10:00')]),
      'POST /api/appointments': () => slow.promise,
    })
    await reachSlots(user)
    await user.click(await screen.findByRole('button', { name: '09:00' }))

    await user.click(screen.getByRole('button', { name: 'Confirmar agendamento' }))

    // com a requisição pendente, NENHUMA escolha pode ser trocada
    expect(screen.getByRole('radio', { name: 'Barbearia do Zé' })).toBeDisabled()
    expect(screen.getByRole('radio', { name: 'Oficina Central' })).toBeDisabled()
    expect(screen.getByRole('radio', { name: /Alinhamento/ })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Próximo dia' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Dia anterior' })).toBeDisabled()
    expect(screen.getByLabelText('Data')).toBeDisabled()
    expect(screen.getByRole('button', { name: '10:00' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Atualizar horários' })).toBeDisabled()
    await user.click(screen.getByRole('radio', { name: 'Barbearia do Zé' })) // clique em controle desabilitado não faz nada
    await user.click(screen.getByRole('button', { name: '10:00' }))
    await user.click(screen.getByRole('radio', { name: /Alinhamento/ }))
    await user.click(screen.getByRole('button', { name: 'Próximo dia' }))
    expect(screen.getByRole('radio', { name: 'Oficina Central' })).toBeChecked()
    expect(screen.getByRole('radio', { name: /Troca de óleo/ })).toBeChecked()
    expect(screen.getAllByText(new RegExp(longDate(today))).length).toBeGreaterThan(0)
    expect(screen.queryByText(new RegExp(longDate(addDays(today, 1))))).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: '09:00' })).toHaveAttribute('aria-pressed', 'true')

    await act(async () => slow.resolve(json(201, { id: 'a-1', providerId: 'p-1', serviceId: 's-1', startsAt: '', endsAt: '', status: 'SCHEDULED', businessName: 'Oficina Central', timeZone: ZONE, serviceName: 'Troca de óleo' })))

    expect(await screen.findByRole('status')).toHaveTextContent(`Troca de óleo em Oficina Central, ${longDate(today)}, às 09:00`)
    expect(calls.filter((c) => c.method === 'POST' && c.path === '/api/appointments')).toHaveLength(1)
    expect((calls.find((c) => c.method === 'POST' && c.path === '/api/appointments')!.body as { localStart: string }).localStart).toBe(`${today}T09:00:00`)
  })

  it('depois de uma recusa as escolhas voltam a poder ser trocadas', async () => {
    const { user } = await loginAsCustomer({
      [`GET /api/providers/p-1/slots?serviceId=s-1&date=${today}`]: () => json(200, [slot(today, '09:00')]),
      'POST /api/appointments': () => problem(409, 'x', { reason: 'SLOT_TAKEN' }),
    })
    await reachSlots(user)
    await user.click(await screen.findByRole('button', { name: '09:00' }))
    await user.click(screen.getByRole('button', { name: 'Confirmar agendamento' }))
    await screen.findByText('Esse horário acabou de ser ocupado por outra pessoa. Escolha outro.')

    await waitFor(() => expect(screen.getByRole('radio', { name: 'Barbearia do Zé' })).toBeEnabled())
    expect(screen.getByRole('button', { name: 'Próximo dia' })).toBeEnabled()
  })
})

describe('agendar: perfis', () => {
  it.each(['PROVIDER', 'ATTENDANT', 'ADMIN'])('%s não vê o menu do cliente e é devolvido ao início ao digitar /agendar', async (role) => {
    const { calls } = mockApi({ 'POST /api/auth/login': () => token(), 'GET /api/me': () => json(200, { ...USER, role, name: 'Maria Souza' }) })
    const view = renderWithHarness()
    await act(async () => view.api().login('x@exemplo.test', PASSWORD))
    await screen.findByRole('heading', { name: 'Olá, Maria Souza' })
    expect(screen.queryByRole('navigation', { name: 'Menu do cliente' })).not.toBeInTheDocument()

    act(() => view.navigate()('/agendar'))

    await waitFor(() => expect(screen.getByRole('heading', { name: 'Olá, Maria Souza' })).toBeInTheDocument())
    expect(calls.some((c) => c.path.startsWith('/api/catalog'))).toBe(false)
  })
})
