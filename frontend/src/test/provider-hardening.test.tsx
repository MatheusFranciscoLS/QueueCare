import { act, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { json, mockApi, PASSWORD, problem, renderApp, renderWithHarness, token, USER, type Call } from './helpers'

afterEach(() => vi.unstubAllGlobals())

const PROVIDER = { ...USER, role: 'PROVIDER', name: 'Oficina Central', email: 'oficina@exemplo.test' }

function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((r) => (resolve = r))
  return { promise, resolve }
}

type Routes = Record<string, (call: Call) => Response | Promise<Response>>

async function loginAsProvider(routes: Routes) {
  const { calls } = mockApi({ 'POST /api/auth/login': () => token(), 'GET /api/me': () => json(200, PROVIDER), ...routes })
  const user = userEvent.setup()
  renderApp('/entrar')
  await user.type(screen.getByLabelText('E-mail'), 'oficina@exemplo.test')
  await user.type(screen.getByLabelText('Senha'), PASSWORD)
  await user.click(screen.getByRole('button', { name: 'Entrar' }))
  await screen.findByRole('heading', { name: 'Olá, Oficina Central' })
  return { calls, user }
}

const menu = (name: string) => screen.getByRole('navigation', { name: 'Menu do prestador' }).querySelector<HTMLAnchorElement>(`a[href="${name}"]`)!

describe('M1: falha ao ATUALIZAR depois de uma gravação bem-sucedida', () => {
  it('serviços: a lista antiga continua visível e o aviso aparece; "Tentar de novo" recarrega', async () => {
    let gets = 0
    const created = { id: 's-2', name: 'Alinhamento', durationMinutes: 30, active: true }
    const { user } = await loginAsProvider({
      'GET /api/provider/services': () => {
        gets += 1
        if (gets === 2) return problem(500, 'fora do ar')
        return json(200, gets === 1 ? [{ id: 's-1', name: 'Troca de óleo', durationMinutes: 60, active: true }] : [{ id: 's-1', name: 'Troca de óleo', durationMinutes: 60, active: true }, created])
      },
      'POST /api/provider/services': () => json(201, created),
    })
    await user.click(menu('/servicos'))
    await screen.findByText('Troca de óleo')

    await user.type(screen.getByLabelText('Nome do serviço'), 'Alinhamento')
    await user.click(screen.getByRole('button', { name: 'Adicionar serviço' }))

    expect(await screen.findByText(/pode estar desatualizado/)).toBeInTheDocument()
    expect(screen.getByText('Troca de óleo')).toBeInTheDocument() // não apagou a tela

    await user.click(screen.getByRole('button', { name: 'Tentar de novo' }))

    expect(await screen.findByText('Alinhamento')).toBeInTheDocument()
    expect(screen.queryByText(/pode estar desatualizado/)).not.toBeInTheDocument()
  })

  it('expediente: o editor continua e o aviso aparece quando o GET depois do salvar falha', async () => {
    let gets = 0
    const { user } = await loginAsProvider({
      'GET /api/provider/availability': () => (++gets === 1 ? json(200, []) : problem(503, 'indisponível')),
      'PUT /api/provider/availability': () => json(200, [{ dayOfWeek: 'FRIDAY', start: '08:00:00', end: '12:00:00' }]),
    })
    await user.click(menu('/expediente'))
    await screen.findByText(/hoje o seu negócio não recebe agendamentos/)
    await user.click(screen.getByRole('button', { name: 'Adicionar horário em Sexta-feira' }))

    await user.click(screen.getByRole('button', { name: 'Salvar expediente' }))

    expect(await screen.findByText(/pode estar desatualizado/)).toBeInTheDocument()
    expect(screen.getByLabelText('Sexta-feira, horário 1, início')).toBeInTheDocument()
  })

  it('negócio: o formulário continua e o aviso aparece quando o GET depois do salvar falha', async () => {
    let gets = 0
    const business = { id: 'b-1', businessName: 'Oficina', timeZone: 'America/Sao_Paulo', minimumNoticeMinutes: 0, cancellationDeadlineMinutes: 0 }
    const { user } = await loginAsProvider({
      'GET /api/provider': () => (++gets === 1 ? json(200, business) : problem(500, 'x')),
      'PUT /api/provider': () => json(200, business),
    })
    await user.click(menu('/meu-negocio'))
    await screen.findByRole('heading', { name: 'Meu negócio' })

    await user.click(screen.getByRole('button', { name: 'Salvar' }))

    expect(await screen.findByText(/pode estar desatualizado/)).toBeInTheDocument()
    expect(screen.getByLabelText('Nome do negócio')).toHaveValue('Oficina')
  })
})

describe('M2: expediente durante a gravação', () => {
  it('bloqueia campos, adicionar e remover enquanto o PUT está pendente', async () => {
    const slowPut = deferred<Response>()
    const { user } = await loginAsProvider({
      'GET /api/provider/availability': () => json(200, [{ dayOfWeek: 'MONDAY', start: '08:00:00', end: '12:00:00' }]),
      'PUT /api/provider/availability': () => slowPut.promise,
    })
    await user.click(menu('/expediente'))
    await screen.findByLabelText('Segunda-feira, horário 1, início')

    await user.click(screen.getByRole('button', { name: 'Salvar expediente' }))

    expect(screen.getByLabelText('Segunda-feira, horário 1, início')).toBeDisabled()
    expect(screen.getByLabelText('Segunda-feira, horário 1, fim')).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Adicionar horário em Terça-feira' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Remover horário 1 de Segunda-feira' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Salvando…' })).toBeDisabled()
    await act(async () => slowPut.resolve(json(200, [{ dayOfWeek: 'MONDAY', start: '08:00:00', end: '12:00:00' }])))
  })

  it('o editor passa a refletir exatamente o que o SERVIDOR devolveu, inclusive quando difere do enviado', async () => {
    const stored = [{ dayOfWeek: 'MONDAY', start: '09:00:00', end: '13:00:00' }]
    const { user } = await loginAsProvider({
      'GET /api/provider/availability': () => json(200, stored),
      'PUT /api/provider/availability': () => json(200, [{ dayOfWeek: 'SATURDAY', start: '10:00:00', end: '14:00:00' }]),
    })
    await user.click(menu('/expediente'))
    await screen.findByLabelText('Segunda-feira, horário 1, início')
    stored.splice(0, 1, { dayOfWeek: 'SATURDAY', start: '10:00:00', end: '14:00:00' }) // o próximo GET devolve o conjunto novo

    await user.click(screen.getByRole('button', { name: 'Salvar expediente' }))

    expect(await screen.findByLabelText('Sábado, horário 1, início')).toHaveValue('10:00')
    expect(screen.queryByLabelText('Segunda-feira, horário 1, início')).not.toBeInTheDocument()
  })

  it('salvar um conjunto IDÊNTICO ao anterior mantém a tela coerente e mostra "salvo"', async () => {
    const windows = [{ dayOfWeek: 'MONDAY', start: '08:00:00', end: '12:00:00' }]
    const { user } = await loginAsProvider({
      'GET /api/provider/availability': () => json(200, windows),
      'PUT /api/provider/availability': () => json(200, windows),
    })
    await user.click(menu('/expediente'))
    await screen.findByLabelText('Segunda-feira, horário 1, início')

    await user.click(screen.getByRole('button', { name: 'Salvar expediente' }))

    expect(await screen.findByText('Expediente salvo.')).toBeVisible()
    expect(screen.getByLabelText('Segunda-feira, horário 1, início')).toHaveValue('08:00')
  })

  it('exatamente 70 horários podem ser salvos e o 71º não pode ser adicionado', async () => {
    // 10 horários por dia, em 7 dias
    const windows = ['MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY', 'SATURDAY', 'SUNDAY'].flatMap((dayOfWeek) =>
      Array.from({ length: 10 }, (_, i) => ({ dayOfWeek, start: `${String(8 + i).padStart(2, '0')}:00:00`, end: `${String(8 + i).padStart(2, '0')}:30:00` })),
    )
    const { calls, user } = await loginAsProvider({
      'GET /api/provider/availability': () => json(200, windows),
      'PUT /api/provider/availability': () => json(200, windows),
    })
    await user.click(menu('/expediente'))
    await screen.findByLabelText('Segunda-feira, horário 10, início')

    expect(screen.getByRole('button', { name: 'Adicionar horário em Segunda-feira' })).toBeDisabled()
    await user.click(screen.getByRole('button', { name: 'Salvar expediente' }))

    await screen.findByText('Expediente salvo.')
    expect((calls.find((c) => c.method === 'PUT')!.body as unknown[]).length).toBe(70)
  })

  it('erro de um dia liga a mensagem ao campo (aria) e leva o foco ao primeiro campo inválido', async () => {
    const { user } = await loginAsProvider({ 'GET /api/provider/availability': () => json(200, []) })
    await user.click(menu('/expediente'))
    await screen.findByText(/hoje o seu negócio não recebe agendamentos/)
    await user.click(screen.getByRole('button', { name: 'Adicionar horário em Quarta-feira' }))
    const start = screen.getByLabelText('Quarta-feira, horário 1, início')
    await user.clear(start) // horário de início vazio

    await user.click(screen.getByRole('button', { name: 'Salvar expediente' }))

    expect(await screen.findByText('Preencha o início e o fim de todos os horários.')).toBeInTheDocument()
    expect(start).toHaveAttribute('aria-invalid', 'true')
    expect(start).toHaveAttribute('aria-describedby', 'day-error-WEDNESDAY')
    expect(start).toHaveFocus()
  })

  it('horário vazio em um dia e inválido em outro: cada dia mostra o seu erro', async () => {
    const { calls, user } = await loginAsProvider({ 'GET /api/provider/availability': () => json(200, []) })
    await user.click(menu('/expediente'))
    await screen.findByText(/hoje o seu negócio não recebe agendamentos/)
    await user.click(screen.getByRole('button', { name: 'Adicionar horário em Segunda-feira' }))
    await user.clear(screen.getByLabelText('Segunda-feira, horário 1, fim'))
    await user.click(screen.getByRole('button', { name: 'Adicionar horário em Terça-feira' }))
    await user.clear(screen.getByLabelText('Terça-feira, horário 1, fim'))
    await user.type(screen.getByLabelText('Terça-feira, horário 1, fim'), '07:00') // fim antes do início (08:00)

    await user.click(screen.getByRole('button', { name: 'Salvar expediente' }))

    expect(await screen.findByText('Preencha o início e o fim de todos os horários.')).toBeInTheDocument()
    expect(screen.getByText('Em cada horário, o início precisa ser antes do fim.')).toBeInTheDocument()
    expect(calls.some((c) => c.method === 'PUT')).toBe(false)
  })
})

describe('L1: erros de campo em serviços sem duplicar no aviso geral', () => {
  it('400 com erro de nome e de duração mostra cada um uma vez, no seu campo', async () => {
    const { user } = await loginAsProvider({
      'GET /api/provider/services': () => json(200, []),
      'POST /api/provider/services': () => problem(400, 'dados inválidos', { reason: 'INVALID_CONFIGURATION', errors: { name: 'nome inválido (servidor)', durationMinutes: 'duração inválida (servidor)' } }),
    })
    await user.click(menu('/servicos'))
    await screen.findByText(/ainda não tem serviços/)
    await user.type(screen.getByLabelText('Nome do serviço'), 'Revisão')

    await user.click(screen.getByRole('button', { name: 'Adicionar serviço' }))

    expect(await screen.findAllByText('nome inválido (servidor)')).toHaveLength(1)
    expect(screen.getAllByText('duração inválida (servidor)')).toHaveLength(1)
  })

  it('400 sem campo conhecido usa o aviso geral', async () => {
    const { user } = await loginAsProvider({
      'GET /api/provider/services': () => json(200, []),
      'POST /api/provider/services': () => problem(400, 'dados inválidos'),
    })
    await user.click(menu('/servicos'))
    await screen.findByText(/ainda não tem serviços/)
    await user.type(screen.getByLabelText('Nome do serviço'), 'Revisão')

    await user.click(screen.getByRole('button', { name: 'Adicionar serviço' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('Confira os dados informados.')
  })
})

describe('edição em andamento durante um recarregamento', () => {
  it('o rascunho de um serviço em edição não é apagado quando a lista recarrega por outra ação', async () => {
    let services = [
      { id: 's-1', name: 'Troca de óleo', durationMinutes: 60, active: true },
      { id: 's-2', name: 'Alinhamento', durationMinutes: 30, active: true },
    ]
    const { user } = await loginAsProvider({
      'GET /api/provider/services': () => json(200, services),
      'POST /api/provider/services': () => {
        services = [...services, { id: 's-3', name: 'Balanceamento', durationMinutes: 20, active: true }]
        return json(201, services[2])
      },
    })
    await user.click(menu('/servicos'))
    await screen.findByText('Troca de óleo')
    await user.click(screen.getAllByRole('button', { name: 'Editar' })[0])
    const name = screen.getByLabelText('Nome')
    await user.clear(name)
    await user.type(name, 'Rascunho meu')

    await user.type(screen.getByLabelText('Nome do serviço'), 'Balanceamento')
    await user.click(screen.getByRole('button', { name: 'Adicionar serviço' }))
    await screen.findByText('Balanceamento')

    expect(screen.getByLabelText('Nome')).toHaveValue('Rascunho meu')
  })
})

describe('acesso direto às telas do prestador por outros perfis', () => {
  it.each(['CUSTOMER', 'ATTENDANT', 'ADMIN'])('%s digitando /meu-negocio, /servicos ou /expediente volta ao início e nada é pedido à API', async (role) => {
    const { calls } = mockApi({ 'POST /api/auth/login': () => token(), 'GET /api/me': () => json(200, { ...USER, role }) })
    const view = renderWithHarness()
    await act(async () => view.api().login('x@exemplo.test', PASSWORD))
    await screen.findByRole('heading', { name: 'Olá, Maria Souza' })

    for (const path of ['/meu-negocio', '/servicos', '/expediente']) {
      act(() => view.navigate()(path))
      await waitFor(() => expect(screen.getByRole('heading', { name: 'Olá, Maria Souza' })).toBeInTheDocument())
    }

    expect(calls.filter((c) => c.path.startsWith('/api/provider'))).toHaveLength(0)
  })
})

describe('janela entre o fim da gravação e o fim do GET posterior', () => {
  it('expediente: continua bloqueado até o GET terminar; depois mostra o que o servidor devolveu', async () => {
    const slowGet = deferred<Response>()
    let gets = 0
    const { user } = await loginAsProvider({
      'GET /api/provider/availability': () => (++gets === 1 ? json(200, [{ dayOfWeek: 'MONDAY', start: '08:00:00', end: '12:00:00' }]) : slowGet.promise),
      'PUT /api/provider/availability': () => json(200, [{ dayOfWeek: 'MONDAY', start: '08:00:00', end: '12:00:00' }]),
    })
    await user.click(menu('/expediente'))
    await screen.findByLabelText('Segunda-feira, horário 1, início')

    await user.click(screen.getByRole('button', { name: 'Salvar expediente' }))
    await screen.findByText('Expediente salvo.') // o PUT terminou; o GET ainda está pendente

    expect(screen.getByLabelText('Segunda-feira, horário 1, início')).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Adicionar horário em Terça-feira' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Remover horário 1 de Segunda-feira' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Salvando…' })).toBeDisabled()

    await act(async () => slowGet.resolve(json(200, [{ dayOfWeek: 'TUESDAY', start: '09:00:00', end: '10:00:00' }])))

    expect(await screen.findByLabelText('Terça-feira, horário 1, início')).toBeEnabled()
    expect(screen.queryByLabelText('Segunda-feira, horário 1, início')).not.toBeInTheDocument()
  })

  it('expediente: se o GET posterior falhar, libera o editor (com o aviso) sem descartar nada', async () => {
    let gets = 0
    const { user } = await loginAsProvider({
      'GET /api/provider/availability': () => (++gets === 1 ? json(200, []) : problem(503, 'fora')),
      'PUT /api/provider/availability': () => json(200, [{ dayOfWeek: 'FRIDAY', start: '08:00:00', end: '12:00:00' }]),
    })
    await user.click(menu('/expediente'))
    await screen.findByText(/hoje o seu negócio não recebe agendamentos/)
    await user.click(screen.getByRole('button', { name: 'Adicionar horário em Sexta-feira' }))
    await user.click(screen.getByRole('button', { name: 'Salvar expediente' }))

    expect(await screen.findByText(/pode estar desatualizado/)).toBeInTheDocument()
    await waitFor(() => expect(screen.getByRole('button', { name: 'Salvar expediente' })).toBeEnabled())
    expect(screen.getByLabelText('Sexta-feira, horário 1, início')).toBeEnabled()
  })

  it('negócio: bloqueia os campos durante a gravação e mostra os valores do SERVIDOR depois', async () => {
    const slowPut = deferred<Response>()
    let stored = { id: 'b-1', businessName: 'Oficina', timeZone: 'America/Sao_Paulo', minimumNoticeMinutes: 0, cancellationDeadlineMinutes: 0 }
    const { user } = await loginAsProvider({
      'GET /api/provider': () => json(200, stored),
      'PUT /api/provider': () => slowPut.promise,
    })
    await user.click(menu('/meu-negocio'))
    await screen.findByRole('heading', { name: 'Meu negócio' })
    await user.type(screen.getByLabelText('Nome do negócio'), ' 2')

    await user.click(screen.getByRole('button', { name: 'Salvar' }))

    expect(screen.getByLabelText('Nome do negócio')).toBeDisabled()
    expect(screen.getByLabelText('Fuso horário')).toBeDisabled()
    expect(screen.getByLabelText('Antecedência mínima (minutos)')).toBeDisabled()
    expect(screen.getByLabelText('Prazo para o cliente cancelar (minutos antes)')).toBeDisabled()
    stored = { ...stored, businessName: 'Oficina 2 (normalizado pelo servidor)', minimumNoticeMinutes: 15 }
    await act(async () => slowPut.resolve(json(200, stored)))

    expect(await screen.findByDisplayValue('Oficina 2 (normalizado pelo servidor)')).toBeEnabled()
    expect(screen.getByLabelText('Antecedência mínima (minutos)')).toHaveValue(15)
    expect(screen.getByText('Salvo.')).toBeVisible()
  })
})

describe('serviços: edição durante o GET posterior à gravação', () => {
  it('"Editar" fica desabilitado até o GET terminar e, depois, os campos partem do que o servidor devolveu', async () => {
    const slowGet = deferred<Response>()
    let gets = 0
    const { user } = await loginAsProvider({
      'GET /api/provider/services': () => (++gets === 1 ? json(200, [{ id: 's-1', name: 'Troca de óleo', durationMinutes: 60, active: true }]) : slowGet.promise),
      'PUT /api/provider/services/s-1': () => json(200, { id: 's-1', name: 'Troca de óleo', durationMinutes: 60, active: false }),
    })
    await user.click(menu('/servicos'))
    await screen.findByText('Troca de óleo')

    await user.click(screen.getByRole('button', { name: 'Desativar' })) // PUT ok, GET pendente

    await waitFor(() => expect(screen.getByRole('button', { name: 'Editar' })).toBeDisabled())
    expect(screen.getByRole('button', { name: 'Desativar' })).toBeDisabled()

    await act(async () => slowGet.resolve(json(200, [{ id: 's-1', name: 'Troca de óleo (normalizado)', durationMinutes: 45, active: false }])))
    await waitFor(() => expect(screen.getByRole('button', { name: 'Editar' })).toBeEnabled())
    await user.click(screen.getByRole('button', { name: 'Editar' }))

    expect(screen.getByLabelText('Nome')).toHaveValue('Troca de óleo (normalizado)')
    expect(document.getElementById('duration-s-1')).toHaveValue(45)
  })
})

describe('serviços: campos bloqueados durante a gravação', () => {
  it('edição: nome, duração, Salvar e Cancelar ficam desabilitados enquanto o PUT está pendente', async () => {
    const slowPut = deferred<Response>()
    const { user } = await loginAsProvider({
      'GET /api/provider/services': () => json(200, [{ id: 's-1', name: 'Troca de óleo', durationMinutes: 60, active: true }]),
      'PUT /api/provider/services/s-1': () => slowPut.promise,
    })
    await user.click(menu('/servicos'))
    await screen.findByText('Troca de óleo')
    await user.click(screen.getByRole('button', { name: 'Editar' }))
    await user.type(screen.getByLabelText('Nome'), ' completa')

    await user.click(screen.getByRole('button', { name: 'Salvar' }))

    expect(screen.getByLabelText('Nome')).toBeDisabled()
    expect(document.getElementById('duration-s-1')).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Salvar' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Cancelar' })).toBeDisabled()
    await act(async () => slowPut.resolve(json(200, { id: 's-1', name: 'Troca de óleo completa', durationMinutes: 60, active: true })))
  })

  it('novo serviço: campos e botão ficam desabilitados enquanto o POST está pendente', async () => {
    const slowPost = deferred<Response>()
    const { user } = await loginAsProvider({
      'GET /api/provider/services': () => json(200, []),
      'POST /api/provider/services': () => slowPost.promise,
    })
    await user.click(menu('/servicos'))
    await screen.findByText(/ainda não tem serviços/)
    await user.type(screen.getByLabelText('Nome do serviço'), 'Revisão')

    await user.click(screen.getByRole('button', { name: 'Adicionar serviço' }))

    expect(screen.getByLabelText('Nome do serviço')).toBeDisabled()
    expect(screen.getByLabelText('Duração (minutos)')).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Salvando…' })).toBeDisabled()
    await act(async () => slowPost.resolve(json(201, { id: 's-9', name: 'Revisão', durationMinutes: 60, active: true })))
  })
})
