import { act, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { addDays, isDate, longDate, timeIn, todayIn } from '../api/dates'
import { generatePassword } from '../auth/generatePassword'
import { validateNewPassword } from '../auth/passwordPolicy'
import { json, mockApi, PASSWORD, problem, renderWithHarness, token, USER, type Call } from './helpers'

// O resumo da tela inicial tem testes próprios (today-summary.test.tsx); aqui ele sairia do caminho das contagens de chamadas à agenda.
vi.mock('../pages/TodaySummary', () => ({ TodaySummary: () => null }))

afterEach(() => vi.unstubAllGlobals())

type Routes = Record<string, (call: Call) => Response | Promise<Response>>

function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((r) => (resolve = r))
  return { promise, resolve }
}

async function loginAs(role: string, routes: Routes) {
  const { calls } = mockApi({ 'POST /api/auth/login': () => token(), 'GET /api/me': () => json(200, { ...USER, role, name: 'Fulana Silva' }), ...routes })
  const view = renderWithHarness()
  await act(async () => view.api().login('x@exemplo.test', PASSWORD))
  await screen.findByRole('heading', { name: 'Olá, Fulana Silva' })
  return { calls, view, user: userEvent.setup() }
}

const menu = (label: string) => within(screen.getByRole('navigation')).getByRole('link', { name: label })

describe('datas e horários (puros)', () => {
  it('hoje é calculado NO FUSO do negócio, não no do aparelho', () => {
    const now = new Date('2026-11-15T02:30:00Z')
    expect(todayIn('America/Sao_Paulo', now)).toBe('2026-11-14')
    expect(todayIn('Asia/Tokyo', now)).toBe('2026-11-15')
  })

  it('soma dias atravessando mês, ano e fevereiro bissexto', () => {
    expect(addDays('2026-11-30', 1)).toBe('2026-12-01')
    expect(addDays('2026-12-31', 1)).toBe('2027-01-01')
    expect(addDays('2028-02-28', 1)).toBe('2028-02-29')
    expect(addDays('2027-03-01', -1)).toBe('2027-02-28')
  })

  it('valida datas reais', () => {
    expect(isDate('2026-11-14')).toBe(true)
    expect(isDate('2026-02-30')).toBe(false)
    expect(isDate('14/11/2026')).toBe(false)
    expect(isDate('')).toBe(false)
  })

  it('mostra o horário no fuso do negócio e a data por extenso', () => {
    expect(timeIn('2026-11-16T11:00:00Z', 'America/Sao_Paulo')).toBe('08:00')
    expect(timeIn('2026-11-16T11:00:00Z', 'Asia/Tokyo')).toBe('20:00')
    expect(longDate('2026-11-16')).toContain('segunda-feira')
  })
})

describe('senha provisória gerada', () => {
  it('é aleatória, cumpre a política e não usa caracteres que se confundem', () => {
    const a = generatePassword()
    const b = generatePassword()
    expect(a).toHaveLength(14)
    expect(a).not.toBe(b)
    expect(validateNewPassword(a)).toBeNull()
    expect(a).toMatch(/^[a-hj-km-np-zA-HJ-KL-NP-Z2-9]+$/)
  })
})

describe('equipe', () => {
  const MEMBER = { userId: 'u-9', name: 'Ana Atendente', email: 'ana@exemplo.test' }

  it('lista a equipe, cria conta de atendente e mostra o aviso', async () => {
    let team = [MEMBER]
    const { calls, user } = await loginAs('PROVIDER', {
      'GET /api/provider/staff': () => json(200, team),
      'POST /api/provider/staff/accounts': (c) => {
        const body = c.body as { name: string; email: string }
        team = [...team, { userId: 'u-10', name: body.name, email: body.email }]
        return json(201, team[1])
      },
    })
    await user.click(menu('Equipe'))
    expect(await screen.findByText('Ana Atendente')).toBeInTheDocument()

    await user.type(screen.getByLabelText('Nome'), 'Bruno Novo')
    await user.type(screen.getByLabelText('E-mail'), 'bruno@exemplo.test')
    await user.type(screen.getByLabelText('Senha provisória'), 'provisoria-123')
    await user.click(screen.getByRole('button', { name: 'Criar conta' }))

    expect((await screen.findAllByText('Bruno Novo')).length).toBeGreaterThan(0) // na lista e no painel de entrega
    expect(screen.getByText(/criada\. Entregue esta senha provisória/)).toBeVisible()
    expect(calls.find((c) => c.path === '/api/provider/staff/accounts')?.body).toEqual({ name: 'Bruno Novo', email: 'bruno@exemplo.test', initialPassword: 'provisoria-123' })
    expect(screen.getByLabelText('Nome')).toHaveValue('') // formulário limpo; a senha não fica na tela
    expect(screen.getAllByLabelText('Senha provisória').some((el) => (el as HTMLInputElement).value === 'provisoria-123')).toBe(true) // fica visível só no painel de entrega
  })

  it('valida antes de enviar e "Gerar senha" preenche uma senha válida e visível', async () => {
    const { calls, user } = await loginAs('PROVIDER', { 'GET /api/provider/staff': () => json(200, []) })
    await user.click(menu('Equipe'))
    await screen.findByText(/ainda não tem atendentes/)

    await user.click(screen.getByRole('button', { name: 'Criar conta' }))
    expect(await screen.findByText('Informe o nome do atendente.')).toBeInTheDocument()
    expect(screen.getByText('Informe um e-mail válido.')).toBeInTheDocument()
    expect(calls.some((c) => c.method === 'POST' && c.path.includes('accounts'))).toBe(false)

    await user.click(screen.getByRole('button', { name: 'Gerar senha' }))
    const field = screen.getByLabelText('Senha provisória')
    expect(field).toHaveAttribute('type', 'text') // visível para copiar
    expect(validateNewPassword((field as HTMLInputElement).value)).toBeNull()
  })

  it('e-mail já usado mostra a resposta uniforme, sem dizer de quem é', async () => {
    const { user } = await loginAs('PROVIDER', {
      'GET /api/provider/staff': () => json(200, []),
      'POST /api/provider/staff/accounts': () => problem(409, 'não foi possível criar a conta com os dados informados', { reason: 'ACCOUNT_CONFLICT' }),
    })
    await user.click(menu('Equipe'))
    await screen.findByText(/ainda não tem atendentes/)
    await user.type(screen.getByLabelText('Nome'), 'Bruno')
    await user.type(screen.getByLabelText('E-mail'), 'ja-existe@exemplo.test')
    await user.type(screen.getByLabelText('Senha provisória'), 'provisoria-123')

    await user.click(screen.getByRole('button', { name: 'Criar conta' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('Não foi possível criar a conta com os dados informados.')
  })

  it('vincular: sucesso, e atendente inexistente ou já vinculado mostram a mensagem certa', async () => {
    let attempt = 0
    const { calls, user } = await loginAs('PROVIDER', {
      'GET /api/provider/staff': () => json(200, []),
      'POST /api/provider/staff': () => {
        attempt += 1
        if (attempt === 1) return problem(404, 'atendente não encontrado', { reason: 'STAFF_NOT_FOUND' })
        if (attempt === 2) return problem(409, 'atendente já vinculado', { reason: 'STAFF_ALREADY_ASSIGNED' })
        return json(201, MEMBER)
      },
    })
    await user.click(menu('Equipe'))
    await screen.findByText(/ainda não tem atendentes/)
    const input = screen.getByLabelText('E-mail do atendente')

    await user.type(input, 'ana@exemplo.test')
    await user.click(screen.getByRole('button', { name: 'Vincular' }))
    expect(await screen.findByText('Não encontramos esse atendente.')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Vincular' }))
    expect(await screen.findByText('Essa pessoa já está vinculada a um prestador.')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Vincular' }))
    expect(await screen.findByText('Atendente vinculado.')).toBeVisible()
    expect(calls.filter((c) => c.method === 'POST' && c.path === '/api/provider/staff')).toHaveLength(3)
  })

  it('desvincular e desativar pedem confirmação: nada é enviado antes dela, e "Voltar" cancela', async () => {
    let team = [MEMBER]
    const { calls, user } = await loginAs('PROVIDER', {
      'GET /api/provider/staff': () => json(200, team),
      'DELETE /api/provider/staff/u-9': () => {
        team = []
        return new Response(null, { status: 204 })
      },
      'POST /api/provider/staff/u-9/deactivate': () => new Response(null, { status: 204 }),
    })
    await user.click(menu('Equipe'))
    await screen.findByText('Ana Atendente')

    await user.click(screen.getByRole('button', { name: 'Desativar conta' }))
    expect(screen.getByText(/perde o acesso/)).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Voltar' }))
    expect(calls.some((c) => c.method === 'POST' && c.path.endsWith('/deactivate'))).toBe(false)

    await user.click(screen.getByRole('button', { name: 'Desvincular' }))
    expect(calls.some((c) => c.method === 'DELETE')).toBe(false) // ainda não confirmou
    await user.click(screen.getByRole('button', { name: 'Confirmar' }))

    expect(await screen.findByText(/Ana Atendente foi desvinculado/)).toBeVisible()
    expect(calls.some((c) => c.method === 'DELETE' && c.path === '/api/provider/staff/u-9')).toBe(true)
    await waitFor(() => expect(screen.queryByRole('button', { name: 'Desvincular' })).not.toBeInTheDocument())
  })

  it('desativar a conta usa o endpoint de desativação e avisa', async () => {
    const { calls, user } = await loginAs('PROVIDER', {
      'GET /api/provider/staff': () => json(200, [MEMBER]),
      'POST /api/provider/staff/u-9/deactivate': () => new Response(null, { status: 204 }),
    })
    await user.click(menu('Equipe'))
    await screen.findByText('Ana Atendente')

    await user.click(screen.getByRole('button', { name: 'Desativar conta' }))
    await user.click(screen.getByRole('button', { name: 'Confirmar' }))

    expect(await screen.findByText(/A conta de Ana Atendente foi desativada/)).toBeVisible()
    expect(calls.some((c) => c.path === '/api/provider/staff/u-9/deactivate')).toBe(true)
  })

  it('durante a gravação os controles ficam bloqueados (criar conta)', async () => {
    const slow = deferred<Response>()
    const { user } = await loginAs('PROVIDER', {
      'GET /api/provider/staff': () => json(200, []),
      'POST /api/provider/staff/accounts': () => slow.promise,
    })
    await user.click(menu('Equipe'))
    await screen.findByText(/ainda não tem atendentes/)
    await user.type(screen.getByLabelText('Nome'), 'Bruno')
    await user.type(screen.getByLabelText('E-mail'), 'bruno@exemplo.test')
    await user.type(screen.getByLabelText('Senha provisória'), 'provisoria-123')

    await user.click(screen.getByRole('button', { name: 'Criar conta' }))

    expect(screen.getByLabelText('Nome')).toBeDisabled()
    expect(screen.getByLabelText('Senha provisória')).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Gerar senha' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Criando…' })).toBeDisabled()
    await act(async () => slow.resolve(json(201, MEMBER)))
  })

  it('sem negócio, manda cadastrar primeiro; falha ao atualizar mantém a lista com aviso', async () => {
    const { user } = await loginAs('PROVIDER', { 'GET /api/provider/staff': () => problem(404, 'x', { reason: 'PROVIDER_NOT_CONFIGURED' }) })
    await user.click(menu('Equipe'))
    expect(await screen.findByText(/Cadastre primeiro o seu negócio/)).toBeInTheDocument()
  })

  it('falha ao atualizar depois de gravar mostra o aviso e o registro afetado já saiu da lista', async () => {
    let gets = 0
    const { user } = await loginAs('PROVIDER', {
      'GET /api/provider/staff': () => (++gets === 2 ? problem(500, 'x') : json(200, [MEMBER])),
      'POST /api/provider/staff/u-9/deactivate': () => new Response(null, { status: 204 }),
    })
    await user.click(menu('Equipe'))
    await screen.findByText('Ana Atendente')
    await user.click(screen.getByRole('button', { name: 'Desativar conta' }))
    await user.click(screen.getByRole('button', { name: 'Confirmar' }))

    expect(await screen.findByText(/pode estar desatualizado/)).toBeInTheDocument()
    expect(screen.queryByText('Ana Atendente')).not.toBeInTheDocument() // a conta foi desativada: não oferece mais ações
  })
})

describe('agenda', () => {
  const WORKPLACE = { businessName: 'Oficina Central', timeZone: 'America/Sao_Paulo' }
  const today = todayIn(WORKPLACE.timeZone)
  const ITEM = { id: 'a-1', startsAt: `${today}T15:00:00Z`, endsAt: `${today}T16:00:00Z`, status: 'SCHEDULED', serviceId: 's-1', serviceName: 'Troca de óleo', customerId: 'c-1', customerName: 'Maria Souza' }

  it('o atendente vê a agenda de hoje com horários no fuso do negócio', async () => {
    const { calls, user } = await loginAs('ATTENDANT', {
      'GET /api/agenda/workplace': () => json(200, WORKPLACE),
      [`GET /api/agenda?date=${today}`]: () => json(200, [ITEM]),
    })
    await user.click(menu('Agenda'))

    expect(await screen.findByText('Troca de óleo', { exact: false })).toBeInTheDocument()
    expect(screen.getByText(/Oficina Central · horários no fuso America\/Sao_Paulo/)).toBeInTheDocument()
    expect(screen.getByText('12:00–13:00')).toBeInTheDocument() // 15:00Z = 12:00 em São Paulo
    expect(screen.getByText('Maria Souza', { exact: false })).toBeInTheDocument()
    expect(screen.getByText('Agendado')).toBeInTheDocument()
    expect(calls.some((c) => c.path === '/api/provider')).toBe(false) // o atendente nunca chama /api/provider
  })

  it('dia anterior, próximo dia e hoje pedem a data certa; dia vazio avisa', async () => {
    const { calls, user } = await loginAs('PROVIDER', {
      'GET /api/agenda/workplace': () => json(200, WORKPLACE),
      [`GET /api/agenda?date=${today}`]: () => json(200, [ITEM]),
      [`GET /api/agenda?date=${addDays(today, -1)}`]: () => json(200, []),
      [`GET /api/agenda?date=${addDays(today, 1)}`]: () => json(200, []),
    })
    await user.click(menu('Agenda'))
    await screen.findByText('12:00–13:00')

    await user.click(screen.getByRole('button', { name: 'Dia anterior' }))
    expect(await screen.findByText('Nenhum agendamento neste dia.')).toBeInTheDocument()
    expect(screen.queryByText('12:00–13:00')).not.toBeInTheDocument() // a lista do outro dia não sobra na tela

    await user.click(screen.getByRole('button', { name: 'Hoje' }))
    expect(await screen.findByText('12:00–13:00')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Próximo dia' }))
    await screen.findByText('Nenhum agendamento neste dia.')
    expect(calls.filter((c) => c.path.startsWith('/api/agenda?date=')).map((c) => c.path)).toEqual([
      `/api/agenda?date=${today}`,
      `/api/agenda?date=${addDays(today, -1)}`,
      `/api/agenda?date=${today}`,
      `/api/agenda?date=${addDays(today, 1)}`,
    ])
  })

  it('resposta lenta de um dia anterior nunca aparece sob a data nova', async () => {
    const slow = deferred<Response>()
    const { user } = await loginAs('PROVIDER', {
      'GET /api/agenda/workplace': () => json(200, WORKPLACE),
      [`GET /api/agenda?date=${today}`]: () => slow.promise,
      [`GET /api/agenda?date=${addDays(today, 1)}`]: () => json(200, []),
    })
    await user.click(menu('Agenda'))
    await screen.findByRole('status') // carregando hoje (lento)
    await user.click(screen.getByRole('button', { name: 'Próximo dia' }))
    await screen.findByText('Nenhum agendamento neste dia.')

    await act(async () => slow.resolve(json(200, [ITEM]))) // chega tarde, do dia de ontem para a tela

    expect(screen.queryByText('12:00–13:00')).not.toBeInTheDocument()
    expect(screen.getByText('Nenhum agendamento neste dia.')).toBeInTheDocument()
  })

  it('ao trocar de dia, a lista do dia anterior some NA HORA (carregando), não fica sob a data nova', async () => {
    const slowNext = deferred<Response>()
    const { user } = await loginAs('PROVIDER', {
      'GET /api/agenda/workplace': () => json(200, WORKPLACE),
      [`GET /api/agenda?date=${today}`]: () => json(200, [ITEM]),
      [`GET /api/agenda?date=${addDays(today, 1)}`]: () => slowNext.promise,
    })
    await user.click(menu('Agenda'))
    await screen.findByText('12:00–13:00')

    await user.click(screen.getByRole('button', { name: 'Próximo dia' }))

    expect(screen.queryByText('12:00–13:00')).not.toBeInTheDocument() // nenhum agendamento de hoje sob a data de amanhã
    expect(screen.getByRole('status')).toHaveTextContent('Carregando')
    await act(async () => slowNext.resolve(json(200, [])))
    expect(await screen.findByText('Nenhum agendamento neste dia.')).toBeInTheDocument()
  })

  it('cancelar pede confirmação, envia o POST e recarrega', async () => {
    let items = [ITEM]
    const { calls, user } = await loginAs('ATTENDANT', {
      'GET /api/agenda/workplace': () => json(200, WORKPLACE),
      [`GET /api/agenda?date=${today}`]: () => json(200, items),
      'POST /api/appointments/a-1/cancel': () => {
        items = []
        return json(200, { id: 'a-1', status: 'CANCELLED' })
      },
    })
    await user.click(menu('Agenda'))
    await screen.findByText('12:00–13:00')

    await user.click(screen.getByRole('button', { name: 'Cancelar agendamento' }))
    expect(calls.some((c) => c.method === 'POST' && c.path.includes('/cancel'))).toBe(false)
    await user.click(screen.getByRole('button', { name: 'Voltar' }))
    expect(screen.getByRole('button', { name: 'Cancelar agendamento' })).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Cancelar agendamento' }))
    await user.click(screen.getByRole('button', { name: 'Confirmar cancelamento' }))

    expect(await screen.findByText('Agendamento de Maria Souza cancelado.')).toBeVisible()
    await waitFor(() => expect(screen.queryByText('12:00–13:00')).not.toBeInTheDocument())
  })

  it.each([
    ['ALREADY_CANCELLED', 409, 'Esse agendamento já foi cancelado.'],
    ['NOT_CANCELLABLE', 409, 'Esse agendamento não pode mais ser cancelado.'],
    ['CONCURRENT_UPDATE', 409, /acabou de ser alterado/],
    ['APPOINTMENT_NOT_FOUND', 404, 'Agendamento não encontrado.'],
  ])('erro %s do cancelamento aparece na linha e o agendamento continua na lista', async (reason, status, message) => {
    const { user } = await loginAs('PROVIDER', {
      'GET /api/agenda/workplace': () => json(200, WORKPLACE),
      [`GET /api/agenda?date=${today}`]: () => json(200, [ITEM]),
      'POST /api/appointments/a-1/cancel': () => problem(status, 'cancelamento recusado', { reason }),
    })
    await user.click(menu('Agenda'))
    await screen.findByText('12:00–13:00')
    await user.click(screen.getByRole('button', { name: 'Cancelar agendamento' }))

    await user.click(screen.getByRole('button', { name: 'Confirmar cancelamento' }))

    expect(await screen.findByText(message)).toBeInTheDocument()
    expect(screen.getByText('12:00–13:00')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Cancelar agendamento' })).toBeEnabled() // pode tentar de novo
  })

  it('só mostra o botão de cancelar em agendamento ainda "Agendado"', async () => {
    const { user } = await loginAs('PROVIDER', {
      'GET /api/agenda/workplace': () => json(200, WORKPLACE),
      [`GET /api/agenda?date=${today}`]: () => json(200, [{ ...ITEM, status: 'CHECKED_IN' }, { ...ITEM, id: 'a-2', status: 'COMPLETED' }]),
    })
    await user.click(menu('Agenda'))
    await screen.findByText('Chegou')

    expect(screen.getByText('Concluído')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Cancelar agendamento' })).not.toBeInTheDocument()
  })

  it('atendente sem vínculo recebe uma explicação; prestador sem negócio, o convite', async () => {
    const first = await loginAs('ATTENDANT', { 'GET /api/agenda/workplace': () => problem(403, 'sem vínculo') })
    await first.user.click(menu('Agenda'))
    expect(await screen.findByText(/ainda não está vinculado a um prestador/)).toBeInTheDocument()
  })

  it('prestador sem negócio vê o convite para cadastrar', async () => {
    const { user } = await loginAs('PROVIDER', { 'GET /api/agenda/workplace': () => problem(404, 'x', { reason: 'PROVIDER_NOT_CONFIGURED' }) })
    await user.click(menu('Agenda'))
    expect(await screen.findByText(/Cadastre primeiro o seu negócio/)).toBeInTheDocument()
  })
})

describe('acesso por perfil às telas novas', () => {
  it('ATTENDANT vê só o menu de agenda e é mandado de volta ao tentar /equipe', async () => {
    const { calls, view } = await loginAs('ATTENDANT', {})
    expect(within(screen.getByRole('navigation', { name: 'Menu do atendente' })).getByRole('link', { name: 'Agenda' })).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'Equipe' })).not.toBeInTheDocument()

    act(() => view.navigate()('/equipe'))

    await waitFor(() => expect(screen.getByRole('heading', { name: 'Olá, Fulana Silva' })).toBeInTheDocument())
    expect(calls.some((c) => c.path.startsWith('/api/provider'))).toBe(false)
  })

  it.each(['CUSTOMER', 'ADMIN'])('%s é mandado de volta ao início ao tentar /agenda ou /equipe (e não vê menu de prestador)', async (role) => {
    const { calls, view } = await loginAs(role, {})
    expect(screen.queryByRole('navigation', { name: 'Menu do prestador' })).not.toBeInTheDocument()
    expect(screen.queryByRole('navigation', { name: 'Menu do atendente' })).not.toBeInTheDocument()

    for (const path of ['/agenda', '/equipe']) {
      act(() => view.navigate()(path))
      await waitFor(() => expect(screen.getByRole('heading', { name: 'Olá, Fulana Silva' })).toBeInTheDocument())
    }

    expect(calls.filter((c) => c.path.startsWith('/api/agenda') || c.path.startsWith('/api/provider'))).toHaveLength(0)
  })
})

describe('depois de gravar com sucesso, o registro afetado deixa de oferecer ações (mesmo se o GET seguinte falhar)', () => {
  it('agenda: o agendamento cancelado some e o aviso de dados desatualizados aparece', async () => {
    let gets = 0
    const today = todayIn('America/Sao_Paulo')
    const item = { id: 'a-1', startsAt: `${today}T15:00:00Z`, endsAt: `${today}T16:00:00Z`, status: 'SCHEDULED', serviceId: 's-1', serviceName: 'Troca de óleo', customerId: 'c-1', customerName: 'Maria Souza' }
    const { user } = await loginAs('ATTENDANT', {
      'GET /api/agenda/workplace': () => json(200, { businessName: 'Oficina', timeZone: 'America/Sao_Paulo' }),
      [`GET /api/agenda?date=${today}`]: () => (++gets === 1 ? json(200, [item]) : problem(500, 'x')),
      'POST /api/appointments/a-1/cancel': () => json(200, { id: 'a-1', status: 'CANCELLED' }),
    })
    await user.click(menu('Agenda'))
    await screen.findByText('12:00–13:00')
    await user.click(screen.getByRole('button', { name: 'Cancelar agendamento' }))

    await user.click(screen.getByRole('button', { name: 'Confirmar cancelamento' }))

    expect(await screen.findByText(/pode estar desatualizado/)).toBeInTheDocument()
    expect(screen.queryByText('12:00–13:00')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Cancelar agendamento' })).not.toBeInTheDocument()
    expect(screen.getByText('Agendamento de Maria Souza cancelado.')).toBeVisible()
  })

  it('equipe: desvinculado ou desativado some e não oferece mais ações', async () => {
    let gets = 0
    const { user } = await loginAs('PROVIDER', {
      'GET /api/provider/staff': () => (++gets === 1 ? json(200, [{ userId: 'u-9', name: 'Ana Atendente', email: 'ana@exemplo.test' }]) : problem(500, 'x')),
      'DELETE /api/provider/staff/u-9': () => new Response(null, { status: 204 }),
    })
    await user.click(menu('Equipe'))
    await screen.findByText('Ana Atendente')
    await user.click(screen.getByRole('button', { name: 'Desvincular' }))

    await user.click(screen.getByRole('button', { name: 'Confirmar' }))

    expect(await screen.findByText(/pode estar desatualizado/)).toBeInTheDocument()
    expect(screen.queryByText('Ana Atendente')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Desvincular' })).not.toBeInTheDocument()
  })
})

describe('botão Hoje com a página aberta de um dia para o outro', () => {
  it('usa a data do MOMENTO do clique, não a de quando a tela foi desenhada', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true, now: new Date('2026-11-14T02:55:00Z') }) // 23:55 de 13/11 em São Paulo
    try {
      const { calls } = mockApi({
        'POST /api/auth/login': () => token('t-1', 7200), // validade longa: o teste avança 20 min de relógio
        'GET /api/me': () => json(200, { ...USER, role: 'PROVIDER', name: 'Fulana Silva' }),
        'GET /api/agenda/workplace': () => json(200, { businessName: 'Oficina', timeZone: 'America/Sao_Paulo' }),
        'GET /api/agenda?date=2026-11-13': () => json(200, []),
        'GET /api/agenda?date=2026-11-14': () => json(200, []),
        'GET /api/agenda?date=2026-11-15': () => json(200, []),
      })
      const view = renderWithHarness()
      await act(async () => view.api().login('x@exemplo.test', PASSWORD))
      await screen.findByRole('heading', { name: 'Olá, Fulana Silva' })
      const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime })
      await user.click(menu('Agenda'))
      await screen.findByText('Nenhum agendamento neste dia.')
      expect(calls.at(-1)?.path).toBe('/api/agenda?date=2026-11-13')

      await act(async () => {
        vi.advanceTimersByTime(20 * 60 * 1000) // passa da meia-noite do negócio (agora 00:15 de 14/11)
      })
      await user.click(screen.getByRole('button', { name: 'Hoje' }))

      await waitFor(() => expect(calls.at(-1)?.path).toBe('/api/agenda?date=2026-11-14')) // o dia NOVO, não o 13
    } finally {
      vi.useRealTimers()
    }
  })
})

describe('senha provisória depois da criação (entrega ao atendente)', () => {
  async function createdPanel(options: { clipboard?: 'ok' | 'fail' } = {}) {
    const { calls, user } = await loginAs('PROVIDER', {
      'GET /api/provider/staff': () => json(200, []),
      'POST /api/provider/staff/accounts': () => json(201, { userId: 'u-10', name: 'Bruno Novo', email: 'bruno@exemplo.test' }),
    })
    await user.click(menu('Equipe'))
    await screen.findByText(/ainda não tem atendentes/)
    await user.type(screen.getByLabelText('Nome'), 'Bruno Novo')
    await user.type(screen.getByLabelText('E-mail'), 'bruno@exemplo.test')
    await user.type(screen.getByLabelText('Senha provisória'), 'provisoria-123')
    await user.click(screen.getByRole('button', { name: 'Criar conta' }))
    await screen.findByText(/criada\. Entregue esta senha provisória/)
    // userEvent instala o seu próprio clipboard: espiona o que está valendo agora
    const writeText = vi.spyOn(navigator.clipboard, 'writeText')
    if (options.clipboard === 'fail') writeText.mockRejectedValue(new Error('negado'))
    return { calls, user, writeText }
  }

  it('a senha enviada continua visível depois do sucesso, com aviso de que não será mostrada de novo', async () => {
    await createdPanel()

    const shown = document.getElementById('created-password') as HTMLInputElement
    expect(shown.value).toBe('provisoria-123')
    expect(shown).toHaveAttribute('readonly')
    expect(screen.getByText(/não será mostrada de novo/)).toBeInTheDocument()
    expect(screen.getByText(/canal seguro/)).toBeInTheDocument()
  })

  it('"Copiar senha" entrega EXATAMENTE a senha enviada à API', async () => {
    const { calls, user, writeText } = await createdPanel()

    await user.click(screen.getByRole('button', { name: 'Copiar senha' }))

    expect(writeText).toHaveBeenCalledWith('provisoria-123')
    expect((calls.find((c) => c.path === '/api/provider/staff/accounts')!.body as { initialPassword: string }).initialPassword).toBe('provisoria-123')
    expect(await screen.findByText('Senha copiada.')).toBeVisible()
  })

  it('se copiar falhar, avisa para copiar manualmente e a senha segue visível', async () => {
    const { user } = await createdPanel({ clipboard: 'fail' })

    await user.click(screen.getByRole('button', { name: 'Copiar senha' }))

    expect(await screen.findByText(/Não foi possível copiar automaticamente/)).toBeVisible()
    expect((document.getElementById('created-password') as HTMLInputElement).value).toBe('provisoria-123')
  })

  it('"Já entreguei" limpa a senha da tela', async () => {
    const { user } = await createdPanel()

    await user.click(screen.getByRole('button', { name: 'Já entreguei, limpar da tela' }))

    expect(document.getElementById('created-password')).toBeNull()
    expect(screen.queryByText('provisoria-123')).not.toBeInTheDocument()
  })

  it('nunca grava a senha em localStorage, sessionStorage ou cookie', async () => {
    await createdPanel()

    expect(JSON.stringify({ ...window.localStorage })).not.toContain('provisoria-123')
    expect(JSON.stringify({ ...window.sessionStorage })).not.toContain('provisoria-123')
    expect(document.cookie).not.toContain('provisoria-123')
  })

  it('uma falha na criação NÃO apresenta a senha como se a conta existisse', async () => {
    const { user } = await loginAs('PROVIDER', {
      'GET /api/provider/staff': () => json(200, []),
      'POST /api/provider/staff/accounts': () => problem(409, 'x', { reason: 'ACCOUNT_CONFLICT' }),
    })
    await user.click(menu('Equipe'))
    await screen.findByText(/ainda não tem atendentes/)
    await user.type(screen.getByLabelText('Nome'), 'Bruno')
    await user.type(screen.getByLabelText('E-mail'), 'ja@exemplo.test')
    await user.type(screen.getByLabelText('Senha provisória'), 'provisoria-123')

    await user.click(screen.getByRole('button', { name: 'Criar conta' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('Não foi possível criar a conta')
    expect(document.getElementById('created-password')).toBeNull()
    expect(screen.queryByText(/criada\. Entregue/)).not.toBeInTheDocument()
  })

  it('a senha some quando o prestador sai da conta', async () => {
    const { user } = await createdPanel()

    await user.click(screen.getByRole('button', { name: 'Sair' }))
    expect(await screen.findByRole('heading', { name: 'Entrar' })).toBeInTheDocument()

    expect(screen.queryByText('provisoria-123')).not.toBeInTheDocument()
    expect(document.getElementById('created-password')).toBeNull()
  })
})
