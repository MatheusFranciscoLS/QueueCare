import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { json, mockApi, PASSWORD, problem, renderApp, token, USER, type Call } from './helpers'

afterEach(() => vi.unstubAllGlobals())

const PROVIDER = { ...USER, role: 'PROVIDER', name: 'Oficina Central', email: 'oficina@exemplo.test' }
const BUSINESS = { id: 'b-1', businessName: 'Oficina Central', timeZone: 'America/Sao_Paulo', minimumNoticeMinutes: 60, cancellationDeadlineMinutes: 120 }
const NOT_CONFIGURED = () => problem(404, 'negócio ainda não cadastrado', { reason: 'PROVIDER_NOT_CONFIGURED' })

/** Entra como prestador e devolve a pessoa e a lista de chamadas. */
async function loginAsProvider(routes: Record<string, (call: Call) => Response | Promise<Response>>, user = userEvent.setup()) {
  const { calls } = mockApi({ 'POST /api/auth/login': () => token(), 'GET /api/me': () => json(200, PROVIDER), ...routes })
  renderApp('/entrar')
  await user.type(screen.getByLabelText('E-mail'), 'oficina@exemplo.test')
  await user.type(screen.getByLabelText('Senha'), PASSWORD)
  await user.click(screen.getByRole('button', { name: 'Entrar' }))
  await screen.findByRole('heading', { name: 'Olá, Oficina Central' })
  return { calls, user }
}

/** Link do MENU (a tela inicial repete os mesmos nomes na lista de atalhos). */
const menuLink = (name: string) => within(screen.getByRole('navigation', { name: 'Menu do prestador' })).getByRole('link', { name })

describe('menu e acesso por perfil', () => {
  it('o prestador vê o menu e os atalhos do início', async () => {
    await loginAsProvider({})
    const menu = screen.getByRole('navigation', { name: 'Menu do prestador' })
    expect(within(menu).getByRole('link', { name: 'Meu negócio' })).toBeInTheDocument()
    expect(within(menu).getByRole('link', { name: 'Serviços' })).toBeInTheDocument()
    expect(within(menu).getByRole('link', { name: 'Expediente' })).toBeInTheDocument()
  })

  it.each(['CUSTOMER', 'ATTENDANT', 'ADMIN'])('%s não vê o menu do prestador', async (role) => {
    mockApi({ 'POST /api/auth/login': () => token(), 'GET /api/me': () => json(200, { ...USER, role }) })
    const user = userEvent.setup()
    renderApp('/entrar')
    await user.type(screen.getByLabelText('E-mail'), 'x@exemplo.test')
    await user.type(screen.getByLabelText('Senha'), PASSWORD)
    await user.click(screen.getByRole('button', { name: 'Entrar' }))
    await screen.findByRole('heading', { name: 'Olá, Maria Souza' })

    expect(screen.queryByRole('navigation', { name: 'Menu do prestador' })).not.toBeInTheDocument()
  })
})

describe('meu negócio', () => {
  it('sem negócio cadastrado, convida a cadastrar e envia POST com os dados', async () => {
    const { calls, user } = await loginAsProvider({
      'GET /api/provider': NOT_CONFIGURED,
      'POST /api/provider': () => json(201, BUSINESS),
    })
    await user.click(menuLink('Meu negócio'))

    expect(await screen.findByRole('heading', { name: 'Cadastre o seu negócio' })).toBeInTheDocument()
    await user.type(screen.getByLabelText('Nome do negócio'), '  Oficina Central ')
    await user.clear(screen.getByLabelText('Antecedência mínima (minutos)'))
    await user.type(screen.getByLabelText('Antecedência mínima (minutos)'), '60')
    await user.clear(screen.getByLabelText('Prazo para o cliente cancelar (minutos antes)'))
    await user.type(screen.getByLabelText('Prazo para o cliente cancelar (minutos antes)'), '120')
    await user.click(screen.getByRole('button', { name: 'Cadastrar negócio' }))

    expect(await screen.findByText('Salvo.')).toBeVisible()
    const post = calls.find((c) => c.method === 'POST' && c.path === '/api/provider')!
    expect(post.body).toEqual({ businessName: 'Oficina Central', timeZone: 'America/Sao_Paulo', minimumNoticeMinutes: 60, cancellationDeadlineMinutes: 120 })
  })

  it('com negócio, edita com PUT; erros de campo da API aparecem no campo', async () => {
    const { calls, user } = await loginAsProvider({
      'GET /api/provider': () => json(200, BUSINESS),
      'PUT /api/provider': () => problem(400, 'dados inválidos', { reason: 'INVALID_CONFIGURATION', errors: { businessName: 'obrigatório, até 160 caracteres' } }),
    })
    await user.click(menuLink('Meu negócio'))
    expect(await screen.findByRole('heading', { name: 'Meu negócio' })).toBeInTheDocument()
    expect(screen.getByLabelText('Nome do negócio')).toHaveValue('Oficina Central')
    expect(screen.getByLabelText('Antecedência mínima (minutos)')).toHaveValue(60)

    await user.type(screen.getByLabelText('Nome do negócio'), ' 2')
    await user.click(screen.getByRole('button', { name: 'Salvar' }))

    expect(await screen.findByText('obrigatório, até 160 caracteres')).toBeInTheDocument()
    expect(calls.some((c) => c.method === 'PUT' && c.path === '/api/provider')).toBe(true)
  })

  it('valida no navegador antes de enviar (nome vazio, minutos inválidos)', async () => {
    const { calls, user } = await loginAsProvider({ 'GET /api/provider': NOT_CONFIGURED })
    await user.click(menuLink('Meu negócio'))
    await screen.findByRole('heading', { name: 'Cadastre o seu negócio' })

    await user.clear(screen.getByLabelText('Antecedência mínima (minutos)'))
    await user.type(screen.getByLabelText('Antecedência mínima (minutos)'), '-5')
    await user.click(screen.getByRole('button', { name: 'Cadastrar negócio' }))

    expect(await screen.findByText('Informe o nome do negócio.')).toBeInTheDocument()
    expect(screen.getByText(/entre 0 e 10080/)).toBeInTheDocument()
    expect(calls.some((c) => c.method === 'POST' && c.path === '/api/provider')).toBe(false)
  })

  it('falha do servidor ao carregar mostra o erro e não a tela de cadastro', async () => {
    const { user } = await loginAsProvider({ 'GET /api/provider': () => problem(500, 'x') })
    await user.click(menuLink('Meu negócio'))
    expect(await screen.findByRole('alert')).toHaveTextContent('Algo deu errado no servidor')
    expect(screen.queryByRole('heading', { name: 'Cadastre o seu negócio' })).not.toBeInTheDocument()
  })
})

describe('serviços', () => {
  it('lista, cria, edita e desativa um serviço', async () => {
    let services = [{ id: 's-1', name: 'Troca de óleo', durationMinutes: 60, active: true }]
    const { calls, user } = await loginAsProvider({
      'GET /api/provider/services': () => json(200, services),
      'POST /api/provider/services': (c) => {
        const body = c.body as { name: string; durationMinutes: number }
        services = [...services, { id: 's-2', ...body, active: true }]
        return json(201, services[1])
      },
      'PUT /api/provider/services/s-1': (c) => {
        const body = c.body as { name: string; durationMinutes: number; active: boolean }
        services = [{ id: 's-1', ...body }]
        return json(200, services[0])
      },
    })
    await user.click(menuLink('Serviços'))
    expect(await screen.findByText('Troca de óleo')).toBeInTheDocument()

    await user.type(screen.getByLabelText('Nome do serviço'), 'Revisão completa')
    await user.clear(screen.getByLabelText('Duração (minutos)'))
    await user.type(screen.getByLabelText('Duração (minutos)'), '120')
    await user.click(screen.getByRole('button', { name: 'Adicionar serviço' }))
    expect(await screen.findByText('Revisão completa')).toBeInTheDocument()
    expect(calls.find((c) => c.method === 'POST' && c.path === '/api/provider/services')?.body).toEqual({ name: 'Revisão completa', durationMinutes: 120 })

    await user.click(screen.getAllByRole('button', { name: 'Desativar' })[0])
    expect(await screen.findByText('Desativado')).toBeInTheDocument()
    expect(calls.find((c) => c.method === 'PUT')?.body).toEqual({ name: 'Troca de óleo', durationMinutes: 60, active: false })
  })

  it('valida nome e duração antes de enviar', async () => {
    const { calls, user } = await loginAsProvider({ 'GET /api/provider/services': () => json(200, []) })
    await user.click(menuLink('Serviços'))
    expect(await screen.findByText('Você ainda não tem serviços. Cadastre o primeiro abaixo.')).toBeInTheDocument()

    await user.clear(screen.getByLabelText('Duração (minutos)'))
    await user.type(screen.getByLabelText('Duração (minutos)'), '3')
    await user.click(screen.getByRole('button', { name: 'Adicionar serviço' }))

    expect(await screen.findByText('Informe o nome do serviço.')).toBeInTheDocument()
    expect(screen.getByText('Informe uma duração entre 5 e 480 minutos.')).toBeInTheDocument()
    expect(calls.some((c) => c.method === 'POST' && c.path === '/api/provider/services')).toBe(false)
  })

  it('sem negócio cadastrado, manda cadastrar primeiro', async () => {
    const { user } = await loginAsProvider({ 'GET /api/provider/services': NOT_CONFIGURED })
    await user.click(menuLink('Serviços'))
    expect(await screen.findByText(/Cadastre primeiro o seu negócio/)).toBeInTheDocument()
  })
})

describe('expediente', () => {
  it('mostra os horários gravados e salva o conjunto COMPLETO ao alterar', async () => {
    let windows = [{ dayOfWeek: 'MONDAY', start: '08:00:00', end: '12:00:00' }]
    const { calls, user } = await loginAsProvider({
      'GET /api/provider/availability': () => json(200, windows),
      'PUT /api/provider/availability': (c) => {
        windows = (c.body as typeof windows).map((w) => ({ ...w, start: `${w.start}:00`, end: `${w.end}:00` }))
        return json(200, windows)
      },
    })
    await user.click(menuLink('Expediente'))
    expect(await screen.findByLabelText('Segunda-feira, horário 1, início')).toHaveValue('08:00')

    await user.click(screen.getByRole('button', { name: 'Adicionar horário em Terça-feira' }))
    await user.click(screen.getByRole('button', { name: 'Salvar expediente' }))

    expect(await screen.findByText('Expediente salvo.')).toBeVisible()
    expect(calls.find((c) => c.method === 'PUT')?.body).toEqual([
      { dayOfWeek: 'MONDAY', start: '08:00', end: '12:00' },
      { dayOfWeek: 'TUESDAY', start: '08:00', end: '12:00' },
    ])
  })

  it('não envia horários sobrepostos nem invertidos e mostra o erro no dia certo', async () => {
    const { calls, user } = await loginAsProvider({ 'GET /api/provider/availability': () => json(200, []) })
    await user.click(menuLink('Expediente'))
    expect(await screen.findByText(/hoje o seu negócio não recebe agendamentos/)).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Adicionar horário em Segunda-feira' }))
    await user.click(screen.getByRole('button', { name: 'Adicionar horário em Segunda-feira' }))
    await user.click(screen.getByRole('button', { name: 'Salvar expediente' }))

    expect(await screen.findByText('Há horários que se sobrepõem neste dia.')).toBeInTheDocument()
    expect(calls.some((c) => c.method === 'PUT')).toBe(false)
  })

  it('remover o último horário e salvar envia lista vazia (negócio fechado)', async () => {
    const { calls, user } = await loginAsProvider({
      'GET /api/provider/availability': () => json(200, [{ dayOfWeek: 'MONDAY', start: '08:00:00', end: '12:00:00' }]),
      'PUT /api/provider/availability': () => json(200, []),
    })
    await user.click(menuLink('Expediente'))
    await screen.findByLabelText('Segunda-feira, horário 1, início')

    await user.click(screen.getByRole('button', { name: 'Remover horário 1 de Segunda-feira' }))
    await user.click(screen.getByRole('button', { name: 'Salvar expediente' }))

    await screen.findByText('Expediente salvo.')
    expect(calls.find((c) => c.method === 'PUT')?.body).toEqual([])
  })

  it('erro do servidor ao salvar aparece e não diz que salvou', async () => {
    const { user } = await loginAsProvider({
      'GET /api/provider/availability': () => json(200, []),
      'PUT /api/provider/availability': () => problem(409, 'recurso ocupado', { reason: 'RESOURCE_BUSY' }),
    })
    await user.click(menuLink('Expediente'))
    await screen.findByText(/hoje o seu negócio não recebe agendamentos/)
    await user.click(screen.getByRole('button', { name: 'Adicionar horário em Sexta-feira' }))

    await user.click(screen.getByRole('button', { name: 'Salvar expediente' }))

    expect(await screen.findByText(/ocupado com outra alteração/)).toBeInTheDocument()
    expect(screen.queryByText('Expediente salvo.')).not.toBeVisible()
  })
})
