import { act, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { json, mockApi, PASSWORD, problem, renderWithHarness, token, USER, type Call } from './helpers'

afterEach(() => vi.unstubAllGlobals())

type Routes = Record<string, (call: Call) => Response | Promise<Response>>

const u = (id: string, name: string, email: string, role = 'CUSTOMER', active = true, mustChangePassword = false) => ({
  id,
  name,
  email,
  role,
  active,
  mustChangePassword,
  createdAt: '2026-11-01T12:00:00Z',
})
const ME = u('u-1', 'Fulana Silva', 'fulana@exemplo.test', 'ADMIN')
const LIST = '/api/admin/users?page=0&size=20'
const NEW_PASSWORD = ['nova', 'senha', 'forte', '99'].join('-')

function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((r) => (resolve = r))
  return { promise, resolve }
}

async function loginAs(role: string, routes: Routes) {
  const { calls } = mockApi({ 'POST /api/auth/login': () => token(), 'GET /api/me': () => json(200, { ...USER, id: 'u-1', role, name: 'Fulana Silva' }), ...routes })
  const view = renderWithHarness()
  await act(async () => view.api().login('x@exemplo.test', PASSWORD))
  await screen.findByRole('heading', { name: 'Olá, Fulana Silva' })
  return { calls, view, user: userEvent.setup() }
}

const menu = (label: string) => within(screen.getByRole('navigation', { name: 'Menu do administrador' })).getByRole('link', { name: label })
const posts = (calls: Call[], suffix: string) => calls.filter((c) => c.method === 'POST' && c.path.endsWith(suffix))
const rowOf = (name: string) => screen.getAllByText(name, { selector: 'strong' }).map((el) => el.closest('li')).find(Boolean) as HTMLElement

describe('contas: lista e busca', () => {
  it('mostra nome, e-mail, perfil e situação; a própria conta não oferece desativar', async () => {
    const { calls, user } = await loginAs('ADMIN', {
      [`GET ${LIST}`]: () => json(200, [ME, u('u-2', 'Oficina Central', 'oficina@exemplo.test', 'PROVIDER', true, true), u('u-3', 'Zeca', 'zeca@exemplo.test', 'CUSTOMER', false)]),
    })
    await user.click(menu('Contas'))

    const rows = await screen.findAllByRole('listitem')
    expect(rows).toHaveLength(3)
    expect(rows[0]).toHaveTextContent('Fulana Silva · fulana@exemplo.test · Administrador Ativa (você)')
    expect(within(rows[0]).queryByRole('button', { name: 'Desativar' })).not.toBeInTheDocument()
    expect(within(rows[0]).getByRole('button', { name: 'Redefinir senha' })).toBeInTheDocument()
    expect(rows[1]).toHaveTextContent('Prestador')
    expect(rows[1]).toHaveTextContent('Precisa trocar a senha')
    expect(within(rows[1]).getByRole('button', { name: 'Desativar' })).toBeInTheDocument()
    expect(rows[2]).toHaveTextContent('Inativa')
    expect(within(rows[2]).getByRole('button', { name: 'Ativar' })).toBeInTheDocument()
    expect(calls.filter((c) => c.path.startsWith('/api/admin/users'))[0].path).toBe(LIST)
  })

  it('buscar por texto e perfil pede a consulta certa; a lista de outra busca nunca aparece sob a nova', async () => {
    const slow = deferred<Response>()
    const { user } = await loginAs('ADMIN', {
      [`GET ${LIST}`]: () => json(200, [ME, u('u-2', 'Ana Lima', 'ana@exemplo.test')]),
      'GET /api/admin/users?q=ofi&role=PROVIDER&page=0&size=20': () => slow.promise,
    })
    await user.click(menu('Contas'))
    await screen.findByText('Ana Lima')

    await user.type(screen.getByLabelText('Nome ou e-mail'), '  ofi ')
    await user.selectOptions(screen.getByLabelText('Perfil'), 'PROVIDER')
    await user.click(screen.getByRole('button', { name: 'Buscar' }))

    expect(screen.queryByText('Ana Lima')).not.toBeInTheDocument() // some NA HORA (carregando), não fica sob a busca nova
    expect(screen.getByRole('status')).toHaveTextContent('Carregando…')
    await act(async () => slow.resolve(json(200, [u('u-5', 'Oficina Central', 'oficina@exemplo.test', 'PROVIDER')])))
    expect(await screen.findByText('Oficina Central')).toBeInTheDocument()
  })

  it('nenhum resultado avisa; falha ao carregar mostra o motivo', async () => {
    const { user } = await loginAs('ADMIN', {
      [`GET ${LIST}`]: () => json(200, [ME]),
      'GET /api/admin/users?q=zzz&page=0&size=20': () => json(200, []),
    })
    await user.click(menu('Contas'))
    await screen.findByRole('listitem')
    await user.type(screen.getByLabelText('Nome ou e-mail'), 'zzz')
    await user.click(screen.getByRole('button', { name: 'Buscar' }))
    expect(await screen.findByText('Nenhuma conta encontrada.')).toBeInTheDocument()
  })

  it('falha ao carregar mostra o motivo, sem lista', async () => {
    const { user } = await loginAs('ADMIN', { [`GET ${LIST}`]: () => problem(500, 'x') })
    await user.click(menu('Contas'))
    expect(await screen.findByRole('alert')).toHaveTextContent('Algo deu errado no servidor')
  })

  it('paginação: próxima só com página cheia; mudar a busca volta à primeira página', async () => {
    const page = (n: number, count: number) => Array.from({ length: count }, (_, i) => u(`p${n}-${i}`, `Pessoa ${n}-${i}`, `p${n}-${i}@exemplo.test`))
    const { calls, user } = await loginAs('ADMIN', {
      [`GET ${LIST}`]: () => json(200, page(0, 20)),
      'GET /api/admin/users?page=1&size=20': () => json(200, page(1, 3)),
      'GET /api/admin/users?q=x&page=0&size=20': () => json(200, page(9, 2)),
    })
    await user.click(menu('Contas'))
    await screen.findByText('Pessoa 0-0')
    expect(screen.getByRole('button', { name: 'Página anterior' })).toBeDisabled()

    await user.click(screen.getByRole('button', { name: 'Próxima página' }))
    expect(await screen.findByText('Pessoa 1-0')).toBeInTheDocument()
    expect(screen.queryByText('Pessoa 0-0')).not.toBeInTheDocument()
    expect(screen.getByText('Página 2')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Próxima página' })).toBeDisabled() // só 3: não há mais
    expect(screen.getByRole('button', { name: 'Página anterior' })).toBeEnabled()

    await user.type(screen.getByLabelText('Nome ou e-mail'), 'x')
    await user.click(screen.getByRole('button', { name: 'Buscar' }))
    expect(await screen.findByText('Pessoa 9-0')).toBeInTheDocument()
    expect(screen.getByText('Página 1')).toBeInTheDocument()
    expect(calls.some((c) => c.path === '/api/admin/users?q=x&page=0&size=20')).toBe(true)
  })
})

describe('contas: desativar e ativar', () => {
  const TARGET = u('u-2', 'Ana Lima', 'ana@exemplo.test', 'ATTENDANT')

  it('desativar pede confirmação (o foco vai para "Voltar"; "Voltar" não envia nada e devolve o foco)', async () => {
    const { calls, user } = await loginAs('ADMIN', { [`GET ${LIST}`]: () => json(200, [ME, TARGET]) })
    await user.click(menu('Contas'))
    await screen.findByText('Ana Lima')

    await user.click(within(rowOf('Ana Lima')).getByRole('button', { name: 'Desativar' }))
    expect(within(rowOf('Ana Lima')).getByRole('button', { name: 'Voltar' })).toHaveFocus()
    expect(screen.getByText(/Desativar a conta de Ana Lima\?/)).toBeInTheDocument()
    await user.click(within(rowOf('Ana Lima')).getByRole('button', { name: 'Voltar' }))
    expect(within(rowOf('Ana Lima')).getByRole('button', { name: 'Desativar' })).toHaveFocus()
    expect(posts(calls, '/deactivate')).toHaveLength(0)
  })

  it('desativar: situação nova na hora (mesmo se o GET seguinte falhar), aviso, foco na linha e um único envio', async () => {
    let gets = 0
    const slow = deferred<Response>()
    const { calls, user } = await loginAs('ADMIN', {
      [`GET ${LIST}`]: () => (++gets === 1 ? json(200, [ME, TARGET]) : problem(500, 'x')),
      'POST /api/admin/users/u-2/deactivate': () => slow.promise,
    })
    await user.click(menu('Contas'))
    await screen.findByText('Ana Lima')
    await user.click(within(rowOf('Ana Lima')).getByRole('button', { name: 'Desativar' }))
    await user.dblClick(within(rowOf('Ana Lima')).getByRole('button', { name: 'Confirmar desativação' }))
    expect(within(rowOf('Ana Lima')).getByRole('button', { name: 'Aguarde…' })).toBeDisabled()
    await act(async () => slow.resolve(new Response(null, { status: 204 })))

    expect(await screen.findByText('A conta de Ana Lima foi desativada.')).toBeInTheDocument()
    expect(within(rowOf('Ana Lima')).getByText('Inativa')).toBeInTheDocument()
    expect(within(rowOf('Ana Lima')).getByRole('button', { name: 'Ativar' })).toBeInTheDocument()
    expect(within(rowOf('Ana Lima')).queryByRole('button', { name: 'Desativar' })).not.toBeInTheDocument()
    expect(rowOf('Ana Lima')).toHaveFocus()
    expect(posts(calls, '/deactivate')).toHaveLength(1)
  })

  it('uma leitura nova bem-sucedida passa a mandar (outra pessoa reativou a conta)', async () => {
    let gets = 0
    const { user } = await loginAs('ADMIN', {
      [`GET ${LIST}`]: () => json(200, [ME, ++gets === 1 ? TARGET : { ...TARGET, active: true }]),
      'POST /api/admin/users/u-2/deactivate': () => new Response(null, { status: 204 }),
    })
    await user.click(menu('Contas'))
    await screen.findByText('Ana Lima')
    await user.click(within(rowOf('Ana Lima')).getByRole('button', { name: 'Desativar' }))
    await user.click(within(rowOf('Ana Lima')).getByRole('button', { name: 'Confirmar desativação' }))

    await screen.findByText('A conta de Ana Lima foi desativada.')
    // a leitura posterior diz Ativa: é o que o servidor diz agora
    await waitFor(() => expect(within(rowOf('Ana Lima')).getByText('Ativa')).toBeInTheDocument())
  })

  it('CORRIDA: leitura iniciada ANTES da confirmação, que termina bem-sucedida e antiga, não desfaz a confirmação (e a leitura posterior falhando também não)', async () => {
    const X = u('u-2', 'Ana Lima', 'ana@exemplo.test', 'ATTENDANT')
    const Y = u('u-3', 'Bia Souza', 'bia@exemplo.test', 'ATTENDANT')
    const state = { x: true, y: true }
    const snapshot = () => [ME, { ...X, active: state.x }, { ...Y, active: state.y }]
    const get3 = deferred<Response>()
    const postY = deferred<Response>()
    let gets = 0
    const { user } = await loginAs('ADMIN', {
      [`GET ${LIST}`]: () => {
        gets += 1
        if (gets === 1) return json(200, snapshot())
        if (gets === 3) return get3.promise // a atualização manual que fica pendente
        return problem(500, 'x') // 2 (depois de desativar a Ana) e 4 (depois de desativar a Bia)
      },
      'POST /api/admin/users/u-2/deactivate': () => {
        state.x = false
        return new Response(null, { status: 204 })
      },
      'POST /api/admin/users/u-3/deactivate': () => postY.promise,
    })
    await user.click(menu('Contas'))
    await screen.findByText('Bia Souza')

    // 1) desativa a Ana; a leitura seguinte (2) falha e a tela avisa
    await user.click(within(rowOf('Ana Lima')).getByRole('button', { name: 'Desativar' }))
    await user.click(within(rowOf('Ana Lima')).getByRole('button', { name: 'Confirmar desativação' }))
    await screen.findByText('Não foi possível atualizar os dados. O que aparece abaixo pode estar desatualizado.')

    // 2) começa uma atualização manual (3) e ela fica PENDENTE
    await user.click(screen.getByRole('button', { name: 'Tentar de novo' }))

    // 3) com ela pendente, desativa a Bia (a resposta do POST também fica pendente)
    await user.click(within(rowOf('Bia Souza')).getByRole('button', { name: 'Desativar' }))
    await user.click(within(rowOf('Bia Souza')).getByRole('button', { name: 'Confirmar desativação' }))

    // 4) a atualização (3) termina BEM-SUCEDIDA, mas com a Bia ainda ativa (foi lida antes da desativação dela)
    await act(async () => get3.resolve(json(200, snapshot())))
    // 5) o POST da Bia termina com sucesso; a leitura seguinte (4) falha
    state.y = false
    await act(async () => postY.resolve(new Response(null, { status: 204 })))

    await screen.findByText('A conta de Bia Souza foi desativada.')
    expect(within(rowOf('Bia Souza')).getByText('Inativa')).toBeInTheDocument()
    expect(within(rowOf('Ana Lima')).getByText('Inativa')).toBeInTheDocument()
    expect(gets).toBe(4)
  })

  it('ativar é direto e a conta passa a Ativa', async () => {
    let active = false
    const { calls, user } = await loginAs('ADMIN', {
      [`GET ${LIST}`]: () => json(200, [ME, { ...TARGET, active }]),
      'POST /api/admin/users/u-2/activate': () => {
        active = true
        return new Response(null, { status: 204 })
      },
    })
    await user.click(menu('Contas'))
    await user.click(await screen.findByRole('button', { name: 'Ativar' }))
    expect(await screen.findByText('A conta de Ana Lima foi ativada.')).toBeInTheDocument()
    expect(within(rowOf('Ana Lima')).getByText('Ativa')).toBeInTheDocument()
    expect(posts(calls, '/activate')).toHaveLength(1)
  })

  it('LAST_ACTIVE_ADMIN mostra o motivo e a linha não muda', async () => {
    const other = u('u-9', 'Outro Admin', 'outro@exemplo.test', 'ADMIN')
    const { user } = await loginAs('ADMIN', {
      [`GET ${LIST}`]: () => json(200, [ME, other]),
      'POST /api/admin/users/u-9/deactivate': () => problem(409, 'x', { reason: 'LAST_ACTIVE_ADMIN' }),
    })
    await user.click(menu('Contas'))
    await screen.findByText('Outro Admin')
    await user.click(within(rowOf('Outro Admin')).getByRole('button', { name: 'Desativar' }))
    await user.click(within(rowOf('Outro Admin')).getByRole('button', { name: 'Confirmar desativação' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('É preciso manter ao menos um administrador ativo.')
    expect(within(rowOf('Outro Admin')).getByText('Ativa')).toBeInTheDocument()
    expect(within(rowOf('Outro Admin')).getByRole('button', { name: 'Desativar' })).toHaveFocus()
  })

  it('404 (a conta sumiu): o motivo continua na tela mesmo com a linha removida pela lista refeita', async () => {
    let gets = 0
    const { user } = await loginAs('ADMIN', {
      [`GET ${LIST}`]: () => json(200, ++gets === 1 ? [ME, TARGET] : [ME]),
      'POST /api/admin/users/u-2/deactivate': () => problem(404, 'x', { reason: 'USER_NOT_FOUND' }),
    })
    await user.click(menu('Contas'))
    await screen.findByText('Ana Lima')
    await user.click(within(rowOf('Ana Lima')).getByRole('button', { name: 'Desativar' }))
    await user.click(within(rowOf('Ana Lima')).getByRole('button', { name: 'Confirmar desativação' }))

    await waitFor(() => expect(screen.queryByText('Ana Lima')).not.toBeInTheDocument())
    expect(screen.getByRole('alert')).toHaveTextContent('Essa conta não foi encontrada.')
  })
})

describe('contas: redefinir senha', () => {
  const TARGET = u('u-2', 'Ana Lima', 'ana@exemplo.test', 'ATTENDANT')

  async function openReset() {
    let must = false // como o servidor: depois da redefinição a conta passa a precisar trocar a senha
    const ctx = await loginAs('ADMIN', {
      [`GET ${LIST}`]: () => json(200, [ME, { ...TARGET, mustChangePassword: must }]),
      'POST /api/admin/users/u-2/reset-password': () => {
        must = true
        return new Response(null, { status: 204 })
      },
    })
    await ctx.user.click(menu('Contas'))
    await screen.findByText('Ana Lima')
    await ctx.user.click(within(rowOf('Ana Lima')).getByRole('button', { name: 'Redefinir senha' }))
    return ctx
  }

  it('o campo da senha recebe o foco; senha curta é recusada sem enviar nada', async () => {
    const { calls, user } = await openReset()
    const field = screen.getByLabelText('Nova senha provisória de Ana Lima')
    expect(field).toHaveFocus()
    await user.type(field, 'curta')
    await user.click(within(rowOf('Ana Lima')).getByRole('button', { name: 'Redefinir senha' }))
    expect(await screen.findByText(/A senha deve ter pelo menos 10 caracteres/)).toBeInTheDocument()
    expect(posts(calls, '/reset-password')).toHaveLength(0)
  })

  it('envia EXATAMENTE a senha digitada, mostra-a uma vez para entrega e marca "precisa trocar"', async () => {
    const { calls, user } = await openReset()
    await user.type(screen.getByLabelText('Nova senha provisória de Ana Lima'), NEW_PASSWORD)
    await user.click(within(rowOf('Ana Lima')).getByRole('button', { name: 'Redefinir senha' }))

    expect(await screen.findByLabelText('Senha provisória')).toHaveValue(NEW_PASSWORD)
    expect(screen.getByText(/Senha de/)).toHaveTextContent('Ana Lima')
    expect((posts(calls, '/reset-password')[0].body as { newInitialPassword: string }).newInitialPassword).toBe(NEW_PASSWORD)
    expect(within(rowOf('Ana Lima')).getByText(/Precisa trocar a senha/)).toBeInTheDocument()
    expect(screen.queryByLabelText('Nova senha provisória de Ana Lima')).not.toBeInTheDocument() // o formulário fechou

    await user.click(screen.getByRole('button', { name: 'Já entreguei, limpar da tela' }))
    expect(screen.queryByLabelText('Senha provisória')).not.toBeInTheDocument()
    expect(document.body.textContent).not.toContain(NEW_PASSWORD)
  })

  it('"Gerar senha" preenche uma senha válida e visível; copiar entrega a mesma senha', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined)
    const { user } = await openReset()
    vi.spyOn(navigator.clipboard, 'writeText').mockImplementation(writeText)
    await user.click(screen.getByRole('button', { name: 'Gerar senha' }))
    const generated = (screen.getByLabelText('Nova senha provisória de Ana Lima') as HTMLInputElement).value
    expect(generated).toHaveLength(14)
    await user.click(within(rowOf('Ana Lima')).getByRole('button', { name: 'Redefinir senha' }))

    await screen.findByLabelText('Senha provisória')
    await user.click(screen.getByRole('button', { name: 'Copiar senha' }))
    expect(writeText).toHaveBeenCalledWith(generated)
    expect(await screen.findByText('Senha copiada.')).toBeInTheDocument()
  })

  it('falha da API mostra o motivo e NÃO apresenta a senha como entregue', async () => {
    const ctx = await loginAs('ADMIN', {
      [`GET ${LIST}`]: () => json(200, [ME, TARGET]),
      'POST /api/admin/users/u-2/reset-password': () => problem(400, 'x', { errors: { newInitialPassword: 'Senha fora da política.' } }),
    })
    await ctx.user.click(menu('Contas'))
    await screen.findByText('Ana Lima')
    await ctx.user.click(within(rowOf('Ana Lima')).getByRole('button', { name: 'Redefinir senha' }))
    await ctx.user.type(screen.getByLabelText('Nova senha provisória de Ana Lima'), NEW_PASSWORD)
    await ctx.user.click(within(rowOf('Ana Lima')).getByRole('button', { name: 'Redefinir senha' }))

    expect(await screen.findByRole('alert')).toBeInTheDocument()
    expect(screen.queryByLabelText('Senha provisória')).not.toBeInTheDocument()
    expect(within(rowOf('Ana Lima')).queryByText(/Precisa trocar a senha/)).not.toBeInTheDocument()
  })

  it('duplo clique em redefinir envia um único pedido', async () => {
    const slow = deferred<Response>()
    const ctx = await loginAs('ADMIN', {
      [`GET ${LIST}`]: () => json(200, [ME, TARGET]),
      'POST /api/admin/users/u-2/reset-password': () => slow.promise,
    })
    await ctx.user.click(menu('Contas'))
    await screen.findByText('Ana Lima')
    await ctx.user.click(within(rowOf('Ana Lima')).getByRole('button', { name: 'Redefinir senha' }))
    await ctx.user.type(screen.getByLabelText('Nova senha provisória de Ana Lima'), NEW_PASSWORD)
    await ctx.user.dblClick(within(rowOf('Ana Lima')).getByRole('button', { name: 'Redefinir senha' }))
    await act(async () => slow.resolve(new Response(null, { status: 204 })))
    await screen.findByLabelText('Senha provisória')
    expect(posts(ctx.calls, '/reset-password')).toHaveLength(1)
  })
})

describe('novo prestador', () => {
  const FILL = async (user: ReturnType<typeof userEvent.setup>) => {
    await user.type(screen.getByLabelText('Nome do prestador'), 'Oficina do Zé')
    await user.type(screen.getByLabelText('E-mail'), 'ze@exemplo.test')
    await user.type(screen.getByLabelText('Senha provisória'), NEW_PASSWORD)
    await user.type(screen.getByLabelText('Nome do negócio'), 'Oficina Central')
    await user.clear(screen.getByLabelText('Antecedência mínima (minutos)'))
    await user.type(screen.getByLabelText('Antecedência mínima (minutos)'), '30')
    await user.clear(screen.getByLabelText('Prazo para o cliente cancelar (minutos antes)'))
    await user.type(screen.getByLabelText('Prazo para o cliente cancelar (minutos antes)'), '120')
  }

  it('valida antes de enviar e leva o foco ao primeiro campo inválido', async () => {
    const { calls, user } = await loginAs('ADMIN', {})
    await user.click(menu('Novo prestador'))
    await user.click(await screen.findByRole('button', { name: 'Criar prestador' }))

    expect(screen.getByText('Informe o nome do prestador.')).toBeInTheDocument()
    expect(screen.getByText('Informe um e-mail válido.')).toBeInTheDocument()
    expect(screen.getByText('Informe o nome do negócio.')).toBeInTheDocument()
    expect(screen.getByLabelText('Nome do prestador')).toHaveFocus()
    expect(posts(calls, '/api/admin/providers')).toHaveLength(0)
  })

  it('cria com os dados digitados, mostra a senha UMA vez e limpa o formulário', async () => {
    const { calls, user } = await loginAs('ADMIN', {
      'POST /api/admin/providers': () =>
        json(201, { userId: 'u-7', name: 'Oficina do Zé', email: 'ze@exemplo.test', providerId: 'p-7', businessName: 'Oficina Central', mustChangePassword: true }),
    })
    await user.click(menu('Novo prestador'))
    await FILL(user)
    await user.selectOptions(screen.getByLabelText('Fuso horário do negócio'), 'America/Sao_Paulo')
    await user.click(screen.getByRole('button', { name: 'Criar prestador' }))

    expect(await screen.findByText(/e negócio/)).toHaveTextContent('Oficina do Zé')
    expect(screen.getAllByLabelText('Senha provisória').some((el) => (el as HTMLInputElement).value === NEW_PASSWORD && el.hasAttribute('readonly'))).toBe(true)
    expect(calls.find((c) => c.path === '/api/admin/providers')!.body).toEqual({
      name: 'Oficina do Zé',
      email: 'ze@exemplo.test',
      initialPassword: NEW_PASSWORD,
      businessName: 'Oficina Central',
      timeZone: 'America/Sao_Paulo',
      minimumNoticeMinutes: 30,
      cancellationDeadlineMinutes: 120,
    })
    expect((screen.getByLabelText('Nome do prestador') as HTMLInputElement).value).toBe('')

    await user.click(screen.getByRole('button', { name: 'Já entreguei, limpar da tela' }))
    expect(document.body.textContent).not.toContain(NEW_PASSWORD)
  })

  it('e-mail já usado mostra a resposta uniforme e NÃO mostra senha entregue', async () => {
    const { user } = await loginAs('ADMIN', { 'POST /api/admin/providers': () => problem(409, 'x', { reason: 'ACCOUNT_CONFLICT' }) })
    await user.click(menu('Novo prestador'))
    await FILL(user)
    await user.click(screen.getByRole('button', { name: 'Criar prestador' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('Não foi possível criar a conta com os dados informados.')
    expect(screen.queryByText(/não será mostrada de novo/)).not.toBeInTheDocument()
    expect((screen.getByLabelText('Nome do prestador') as HTMLInputElement).value).toBe('Oficina do Zé') // o que foi digitado fica para corrigir
  })

  it('erros de campo da API aparecem no campo', async () => {
    const { user } = await loginAs('ADMIN', {
      'POST /api/admin/providers': () => problem(400, 'x', { errors: { businessName: 'Nome do negócio inválido.' } }),
    })
    await user.click(menu('Novo prestador'))
    await FILL(user)
    await user.click(screen.getByRole('button', { name: 'Criar prestador' }))
    expect(await screen.findByText('Nome do negócio inválido.')).toBeInTheDocument()
  })

  it('duplo clique em criar envia um único pedido e bloqueia o botão', async () => {
    const slow = deferred<Response>()
    const { calls, user } = await loginAs('ADMIN', { 'POST /api/admin/providers': () => slow.promise })
    await user.click(menu('Novo prestador'))
    await FILL(user)
    await user.dblClick(screen.getByRole('button', { name: 'Criar prestador' }))
    expect(screen.getByRole('button', { name: 'Criando…' })).toBeDisabled()
    await act(async () =>
      slow.resolve(json(201, { userId: 'u-7', name: 'Oficina do Zé', email: 'ze@exemplo.test', providerId: 'p-7', businessName: 'Oficina Central', mustChangePassword: true })),
    )
    await screen.findByText(/e negócio/)
    expect(posts(calls, '/api/admin/providers')).toHaveLength(1)
  })
})

describe('acesso por perfil às telas de administração', () => {
  it.each(['PROVIDER', 'ATTENDANT', 'CUSTOMER'])('%s não vê o menu do administrador e é mandado de volta ao início, sem chamar a API de administração', async (role) => {
    const { calls, view } = await loginAs(role, {})
    expect(screen.queryByRole('navigation', { name: 'Menu do administrador' })).not.toBeInTheDocument()

    for (const path of ['/admin/contas', '/admin/prestadores/novo']) {
      act(() => view.navigate()(path))
      await waitFor(() => expect(screen.getByRole('heading', { name: 'Olá, Fulana Silva' })).toBeInTheDocument())
    }
    expect(calls.filter((c) => c.path.startsWith('/api/admin'))).toHaveLength(0)
  })

  it('ADMIN vê só o menu de administração', async () => {
    await loginAs('ADMIN', {})
    expect(screen.getByRole('navigation', { name: 'Menu do administrador' })).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'Agenda' })).not.toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'Equipe' })).not.toBeInTheDocument()
  })
})
