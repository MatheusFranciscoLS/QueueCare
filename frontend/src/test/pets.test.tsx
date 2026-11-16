import { act, fireEvent, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { addDays, todayIn } from '../api/dates'
import { json, mockApi, PASSWORD, problem, renderWithHarness, token, USER, type Call } from './helpers'

// O resumo da tela inicial tem testes próprios; aqui ele sairia do caminho das contagens de chamadas à agenda.
vi.mock('../pages/TodaySummary', () => ({ TodaySummary: () => null }))

afterEach(() => vi.unstubAllGlobals())

type Routes = Record<string, (call: Call) => Response | Promise<Response>>

const ZONE = 'America/Sao_Paulo'
const today = todayIn(ZONE)
const REX = { id: 'pet-1', name: 'Rex', species: 'DOG', breed: 'Poodle', size: 'MEDIUM', notes: 'Tem coceira na pata.', active: true }
const MEL = { id: 'pet-2', name: 'Mel', species: 'CAT', breed: null, size: null, notes: null, active: true }
const BOB = { id: 'pet-3', name: 'Bob', species: 'DOG', breed: null, size: 'LARGE', notes: null, active: false }

function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((r) => (resolve = r))
  return { promise, resolve }
}

async function loginAs(role: string, routes: Routes) {
  const { calls } = mockApi({
    'POST /api/auth/login': () => token(),
    'GET /api/me': () => json(200, { ...USER, role, name: 'Maria Souza' }),
    'GET /api/agenda/workplace': () => json(200, { businessName: 'Estúdio Pelo e Ponto', timeZone: ZONE }),
    ...routes,
  })
  const view = renderWithHarness()
  await act(async () => view.api().login('x@exemplo.test', PASSWORD))
  await screen.findByRole('heading', { name: 'Olá, Maria Souza' })
  return { calls, view, user: userEvent.setup() }
}

const calls = (all: Call[], method: string, path: string) => all.filter((c) => c.method === method && c.path === path)

async function openPets(routes: Routes) {
  const ctx = await loginAs('CUSTOMER', routes)
  await ctx.user.click(within(screen.getByRole('navigation', { name: 'Menu do cliente' })).getByRole('link', { name: 'Meus pets' }))
  await screen.findByRole('heading', { name: 'Meus pets' })
  return ctx
}

async function fillNewPet(user: ReturnType<typeof userEvent.setup>, values: { name?: string; species?: string; breed?: string; size?: string; notes?: string }) {
  if (values.name !== undefined) await user.type(screen.getByLabelText('Nome do pet'), values.name)
  if (values.species) await user.selectOptions(screen.getByLabelText('Espécie'), values.species)
  if (values.breed) await user.type(screen.getByLabelText('Raça (opcional)'), values.breed)
  if (values.size) await user.selectOptions(screen.getByLabelText('Porte (opcional)'), values.size)
  if (values.notes) await user.type(screen.getByLabelText('Observações (opcional)'), values.notes)
}

describe('meus pets: lista', () => {
  it('mostra os pets ativos com espécie, raça, porte e observações, e o contador', async () => {
    await openPets({ 'GET /api/customer/pets': () => json(200, [REX, MEL]) })
    const rows = await screen.findAllByRole('listitem')
    expect(rows).toHaveLength(2)
    expect(rows[0]).toHaveTextContent('Rex')
    expect(rows[0]).toHaveTextContent('Cachorro · Poodle · Médio')
    expect(rows[0]).toHaveTextContent('Observações: Tem coceira na pata.')
    expect(rows[1]).toHaveTextContent('Mel')
    expect(rows[1]).toHaveTextContent('Gato')
    expect(rows[1]).not.toHaveTextContent('Observações')
    expect(screen.getByText(/2 de 20 pets ativos/)).toBeInTheDocument()
  })

  it('sem pets: convite para cadastrar o primeiro', async () => {
    await openPets({ 'GET /api/customer/pets': () => json(200, []) })
    expect(await screen.findByText('Você ainda não tem pets ativos. Cadastre o primeiro abaixo.')).toBeInTheDocument()
  })

  it('"Mostrar arquivados" pede a lista completa e marca os arquivados', async () => {
    const { user } = await openPets({
      'GET /api/customer/pets': () => json(200, [REX]),
      'GET /api/customer/pets?includeArchived=true': () => json(200, [REX, BOB]),
    })
    await user.click(await screen.findByRole('checkbox', { name: /Mostrar arquivados/ }))
    const bob = (await screen.findByText('Bob')).closest('li')!
    expect(within(bob).getByText('Arquivado')).toBeInTheDocument()
    expect(within(bob).getByRole('button', { name: 'Reativar' })).toBeInTheDocument()
    expect(screen.getByText(/1 de 20 pets ativos/)).toBeInTheDocument()
  })

  it('a resposta antiga de um filtro nunca aparece sob o filtro novo', async () => {
    const slowActive = deferred<Response>()
    let activeCalls = 0
    const { user } = await openPets({
      'GET /api/customer/pets': () => (++activeCalls === 1 ? json(200, [REX]) : slowActive.promise),
      'GET /api/customer/pets?includeArchived=true': () => json(200, [REX, BOB]),
    })
    // primeira carga (já respondida); agora alterna o filtro duas vezes, com a lista "só ativos" lenta na segunda volta
    const checkbox = await screen.findByRole('checkbox', { name: /Mostrar arquivados/ })
    await user.click(checkbox) // pede a lista completa
    await screen.findByText('Bob')
    await user.click(checkbox) // volta para só ativos: a resposta demora
    await act(async () => slowActive.resolve(json(200, [REX])))
    await waitFor(() => expect(screen.queryByText('Bob')).not.toBeInTheDocument())
  })
})

describe('meus pets: cadastrar', () => {
  it('envia os dados digitados, esvazia o formulário e mostra o pet novo', async () => {
    let listed: unknown[] = [MEL]
    const { calls: all, user } = await openPets({
      'GET /api/customer/pets': () => json(200, listed),
      'POST /api/customer/pets': () => {
        listed = [REX, MEL]
        return json(201, REX)
      },
    })
    await screen.findByText('Mel')
    await fillNewPet(user, { name: '  Rex  ', species: 'DOG', breed: 'Poodle', size: 'MEDIUM', notes: 'Tem coceira na pata.' })

    await user.click(screen.getByRole('button', { name: 'Cadastrar pet' }))

    expect(await screen.findByText('Rex cadastrado.')).toBeInTheDocument()
    expect(calls(all, 'POST', '/api/customer/pets')[0].body).toEqual({ name: '  Rex  ', species: 'DOG', breed: 'Poodle', size: 'MEDIUM', notes: 'Tem coceira na pata.' })
    expect(await screen.findByText('Observações: Tem coceira na pata.')).toBeInTheDocument()
    expect(screen.getByLabelText('Nome do pet')).toHaveValue('')
  })

  it('confere nome e espécie antes de enviar e não chama a API', async () => {
    const { calls: all, user } = await openPets({ 'GET /api/customer/pets': () => json(200, []) })
    await screen.findByText(/Cadastre o primeiro/)

    await user.click(screen.getByRole('button', { name: 'Cadastrar pet' }))

    expect(await screen.findByText('Informe o nome do pet.')).toBeInTheDocument()
    expect(screen.getByText('Escolha cachorro ou gato.')).toBeInTheDocument()
    expect(screen.getByLabelText('Nome do pet')).toHaveAttribute('aria-invalid', 'true')
    expect(calls(all, 'POST', '/api/customer/pets')).toHaveLength(0)
  })

  it('observações maiores que 500 caracteres são recusadas antes de enviar', async () => {
    const { calls: all, user } = await openPets({ 'GET /api/customer/pets': () => json(200, []) })
    await screen.findByText(/Cadastre o primeiro/)
    await fillNewPet(user, { name: 'Rex', species: 'DOG' })
    const notes = screen.getByLabelText('Observações (opcional)')
    await user.click(notes)
    await user.paste('x'.repeat(501))

    await user.click(screen.getByRole('button', { name: 'Cadastrar pet' }))

    expect(await screen.findByText('No máximo 500 caracteres.')).toBeInTheDocument()
    expect(calls(all, 'POST', '/api/customer/pets')).toHaveLength(0)
  })

  it('erro de campo da API aparece no campo, sem repetir o texto digitado', async () => {
    const { user } = await openPets({
      'GET /api/customer/pets': () => json(200, []),
      'POST /api/customer/pets': () => problem(400, 'dados inválidos', { reason: 'INVALID_PET', errors: { notes: 'caracteres inválidos' } }),
    })
    await screen.findByText(/Cadastre o primeiro/)
    await fillNewPet(user, { name: 'Rex', species: 'DOG', notes: 'texto privado' })

    await user.click(screen.getByRole('button', { name: 'Cadastrar pet' }))

    expect(await screen.findByText('caracteres inválidos')).toBeInTheDocument()
    expect(screen.getByLabelText('Observações (opcional)')).toHaveAttribute('aria-invalid', 'true')
    expect(screen.getByLabelText('Nome do pet')).toHaveValue('Rex') // o que foi digitado fica para corrigir
  })

  it('limite de pets (409) mostra o motivo', async () => {
    const { user } = await openPets({
      'GET /api/customer/pets': () => json(200, []),
      'POST /api/customer/pets': () => problem(409, 'limite de 20 pets ativos', { reason: 'PET_LIMIT_REACHED' }),
    })
    await screen.findByText(/Cadastre o primeiro/)
    await fillNewPet(user, { name: 'Rex', species: 'DOG' })

    await user.click(screen.getByRole('button', { name: 'Cadastrar pet' }))

    expect(await screen.findByText(/chegou ao limite de pets/)).toBeInTheDocument()
  })

  it('um segundo clique durante o envio não cadastra duas vezes', async () => {
    const pending = deferred<Response>()
    const { calls: all, user } = await openPets({
      'GET /api/customer/pets': () => json(200, []),
      'POST /api/customer/pets': () => pending.promise,
    })
    await screen.findByText(/Cadastre o primeiro/)
    await fillNewPet(user, { name: 'Rex', species: 'DOG' })
    const button = screen.getByRole('button', { name: 'Cadastrar pet' })

    await user.click(button)
    expect(await screen.findByRole('button', { name: 'Cadastrando…' })).toBeDisabled()
    // um segundo envio do formulário (Enter repetido) antes de o primeiro terminar é ignorado, mesmo com o botão já bloqueado
    fireEvent.submit(screen.getByRole('form', { name: 'Cadastrar pet' }))

    expect(calls(all, 'POST', '/api/customer/pets')).toHaveLength(1)
    await act(async () => pending.resolve(json(201, REX)))
  })
})

describe('meus pets: editar, arquivar e reativar', () => {
  it('editar abre o formulário com os dados atuais e envia a substituição', async () => {
    let current = REX
    const { calls: all, user } = await openPets({
      'GET /api/customer/pets': () => json(200, [current]),
      'PUT /api/customer/pets/pet-1': () => {
        current = { ...REX, name: 'Rex Jr' }
        return json(200, current)
      },
    })
    await user.click(await screen.findByRole('button', { name: 'Editar' }))
    const form = screen.getByRole('form', { name: 'Editar Rex' })
    const name = within(form).getByLabelText('Nome do pet')
    expect(name).toHaveValue('Rex')
    expect(within(form).getByLabelText('Raça (opcional)')).toHaveValue('Poodle')
    await user.clear(name)
    await user.type(name, 'Rex Jr')

    await user.click(within(form).getByRole('button', { name: 'Salvar' }))

    expect(await screen.findByText('Rex Jr atualizado.')).toBeInTheDocument()
    expect(calls(all, 'PUT', '/api/customer/pets/pet-1')[0].body).toEqual({ name: 'Rex Jr', species: 'DOG', breed: 'Poodle', size: 'MEDIUM', notes: 'Tem coceira na pata.' })
    expect(screen.queryByRole('form', { name: 'Editar Rex' })).not.toBeInTheDocument()
    expect(await screen.findByText('Rex Jr')).toBeInTheDocument()
  })

  it('"Cancelar" na edição volta sem enviar nada', async () => {
    const { calls: all, user } = await openPets({ 'GET /api/customer/pets': () => json(200, [REX]) })
    await user.click(await screen.findByRole('button', { name: 'Editar' }))
    await user.click(within(screen.getByRole('form', { name: 'Editar Rex' })).getByRole('button', { name: 'Cancelar' }))
    expect(all.filter((c) => c.method === 'PUT')).toHaveLength(0)
    expect(screen.getByRole('button', { name: 'Editar' })).toBeInTheDocument()
  })

  it('arquivar pede confirmação (com o aviso sobre agendamentos), manda o foco para "Voltar" e depois para o título quando a linha some', async () => {
    let listed = [REX, MEL]
    const { calls: all, user } = await openPets({
      'GET /api/customer/pets': () => json(200, listed),
      'POST /api/customer/pets/pet-1/archive': () => {
        listed = [MEL]
        return json(200, { ...REX, active: false })
      },
    })
    const row = (await screen.findByText('Rex')).closest('li')!
    await user.click(within(row).getByRole('button', { name: 'Arquivar' }))
    expect(within(row).getByText(/Agendamentos já feitos continuam valendo/)).toBeInTheDocument()
    expect(within(row).getByRole('button', { name: 'Voltar' })).toHaveFocus()
    expect(calls(all, 'POST', '/api/customer/pets/pet-1/archive')).toHaveLength(0)

    await user.click(within(row).getByRole('button', { name: 'Confirmar arquivamento' }))

    expect(await screen.findByText('Rex arquivado.')).toBeInTheDocument()
    await waitFor(() => expect(screen.queryByText('Rex')).not.toBeInTheDocument())
    expect(screen.getByRole('heading', { name: 'Meus pets' })).toHaveFocus() // a linha sumiu: o foco não se perde
  })

  it('com os arquivados à vista, arquivar mantém a linha e leva o foco para ela', async () => {
    let archived = false
    const { user } = await openPets({
      'GET /api/customer/pets': () => json(200, [REX]),
      'GET /api/customer/pets?includeArchived=true': () => json(200, [{ ...REX, active: !archived }]),
      'POST /api/customer/pets/pet-1/archive': () => {
        archived = true
        return json(200, { ...REX, active: false })
      },
    })
    await user.click(await screen.findByRole('checkbox', { name: /Mostrar arquivados/ }))
    await user.click(await screen.findByRole('button', { name: 'Arquivar' }))
    await user.click(screen.getByRole('button', { name: 'Confirmar arquivamento' }))

    expect(await screen.findByText('Rex arquivado.')).toBeInTheDocument()
    // só depois de a lista refeita mostrar o pet arquivado: o foco precisa ter ficado na linha (e não ter sido roubado pelo botão antigo)
    const row = (await screen.findByText('Arquivado')).closest('li')!
    expect(row).toHaveFocus()
  })

  it('"Voltar" fecha a confirmação sem arquivar e devolve o foco para "Arquivar"', async () => {
    const { calls: all, user } = await openPets({ 'GET /api/customer/pets': () => json(200, [REX]) })
    await user.click(await screen.findByRole('button', { name: 'Arquivar' }))
    await user.click(screen.getByRole('button', { name: 'Voltar' }))
    expect(screen.getByRole('button', { name: 'Arquivar' })).toHaveFocus()
    expect(all.filter((c) => c.method === 'POST' && c.path.startsWith('/api/customer'))).toHaveLength(0)
  })

  it('erro ao arquivar fica na lista (a linha pode sumir) e a lista é refeita', async () => {
    let listed = [REX]
    const { user } = await openPets({
      'GET /api/customer/pets': () => json(200, listed),
      'POST /api/customer/pets/pet-1/archive': () => {
        listed = []
        return problem(404, 'pet não encontrado', { reason: 'PET_NOT_FOUND' })
      },
    })
    await user.click(await screen.findByRole('button', { name: 'Arquivar' }))
    await user.click(screen.getByRole('button', { name: 'Confirmar arquivamento' }))

    expect(await screen.findByText('Não encontramos esse pet. Atualize a lista e escolha de novo.')).toBeInTheDocument()
    await waitFor(() => expect(screen.queryByText('Rex')).not.toBeInTheDocument())
    // a linha sumiu depois do erro: o foco vai para o título (não fica num botão que deixou de existir)
    await waitFor(() => expect(screen.getByRole('heading', { name: 'Meus pets' })).toHaveFocus())
  })

  it('reativar manda a chamada certa e o pet volta ao normal', async () => {
    let archived = true
    const { calls: all, user } = await openPets({
      'GET /api/customer/pets': () => json(200, [REX]),
      'GET /api/customer/pets?includeArchived=true': () => json(200, [REX, { ...BOB, active: !archived }]),
      'POST /api/customer/pets/pet-3/reactivate': () => {
        archived = false
        return json(200, { ...BOB, active: true })
      },
    })
    await user.click(await screen.findByRole('checkbox', { name: /Mostrar arquivados/ }))
    await user.click(await screen.findByRole('button', { name: 'Reativar' }))

    expect(await screen.findByText('Bob reativado.')).toBeInTheDocument()
    expect(calls(all, 'POST', '/api/customer/pets/pet-3/reactivate')).toHaveLength(1)
    await waitFor(() => expect(screen.queryByText('Arquivado')).not.toBeInTheDocument())
  })

  it('reativar com 20 pets ativos (409) mostra o limite e o pet continua arquivado', async () => {
    const { user } = await openPets({
      'GET /api/customer/pets': () => json(200, [REX]),
      'GET /api/customer/pets?includeArchived=true': () => json(200, [REX, BOB]),
      'POST /api/customer/pets/pet-3/reactivate': () => problem(409, 'limite', { reason: 'PET_LIMIT_REACHED' }),
    })
    await user.click(await screen.findByRole('checkbox', { name: /Mostrar arquivados/ }))
    await user.click(await screen.findByRole('button', { name: 'Reativar' }))
    expect(await screen.findByText(/chegou ao limite de pets/)).toBeInTheDocument()
    expect(screen.getByText('Arquivado')).toBeInTheDocument()
    // o erro não tira o foco da linha: ela continua na lista
    await waitFor(() => expect(screen.getByText('Arquivado').closest('li')).toHaveFocus())
  })
})

describe('meus pets: perfis', () => {
  it.each(["PROVIDER", "ATTENDANT", "ADMIN"])("%s não vê o menu nem abre a tela de pets", async (role) => {
    const { view } = await loginAs(role, {})
    expect(screen.queryByRole("link", { name: "Meus pets" })).not.toBeInTheDocument()
    act(() => view.navigate()("/pets"))
    expect(screen.queryByRole("heading", { name: "Meus pets" })).not.toBeInTheDocument()
  })
})

describe('agendar: escolha do pet', () => {
  const PROVIDERS = [{ id: 'p-1', businessName: 'Estúdio Pelo e Ponto', timeZone: ZONE }]
  const SERVICES = [{ id: 's-1', name: 'Banho e tosa', durationMinutes: 60 }]
  const SLOTS = `GET /api/providers/p-1/slots?serviceId=s-1&date=${today}`
  const slot = { localStart: `${today}T09:00:00`, startsAt: `${today}T12:00:00Z`, endsAt: `${today}T13:00:00Z` }
  const booked = { id: 'a-1', providerId: 'p-1', serviceId: 's-1', startsAt: slot.startsAt, endsAt: slot.endsAt, status: 'SCHEDULED', businessName: 'Estúdio Pelo e Ponto', timeZone: ZONE, serviceName: 'Banho e tosa', pet: { name: 'Rex', species: 'DOG' } }

  async function openBook(routes: Routes) {
    const ctx = await loginAs('CUSTOMER', {
      'GET /api/catalog/providers?page=0&size=50': () => json(200, PROVIDERS),
      'GET /api/catalog/providers/p-1/services': () => json(200, SERVICES),
      [SLOTS]: () => json(200, [slot]),
      ...routes,
    })
    await ctx.user.click(within(screen.getByRole('navigation', { name: 'Menu do cliente' })).getByRole('link', { name: 'Agendar' }))
    await ctx.user.click(await screen.findByRole('radio', { name: 'Estúdio Pelo e Ponto' }))
    await ctx.user.click(await screen.findByRole('radio', { name: /Banho e tosa/ }))
    await ctx.user.click(await screen.findByRole('button', { name: '09:00' }))
    return ctx
  }

  it('com um só pet ativo ele já vem escolhido e vai na reserva', async () => {
    const { calls: all, user } = await openBook({ 'GET /api/customer/pets': () => json(200, [REX]), 'POST /api/appointments': () => json(201, booked) })
    expect(await screen.findByRole('radio', { name: /Rex · Cachorro · Poodle · Médio/ })).toBeChecked()
    expect(screen.getByRole('region', { name: 'Confirmar agendamento' })).toHaveTextContent('para Rex')

    await user.click(screen.getByRole('button', { name: 'Confirmar agendamento' }))

    expect(await screen.findByRole('heading', { name: 'Agendamento confirmado' })).toBeInTheDocument()
    expect(screen.getByRole('status')).toHaveTextContent(/Banho e tosa para Rex em Estúdio Pelo e Ponto/)
    expect(calls(all, 'POST', '/api/appointments')[0].body).toEqual({ providerId: 'p-1', serviceId: 's-1', localStart: slot.localStart, petId: 'pet-1' })
  })

  it('com vários pets nenhum vem escolhido: confirmar só habilita depois da escolha, e a reserva leva o escolhido', async () => {
    const { calls: all, user } = await openBook({ 'GET /api/customer/pets': () => json(200, [REX, MEL]), 'POST /api/appointments': () => json(201, booked) })
    const confirm = await screen.findByRole('button', { name: 'Confirmar agendamento' })
    expect(screen.getByRole('radio', { name: /Rex/ })).not.toBeChecked()
    expect(screen.getByRole('radio', { name: /Mel/ })).not.toBeChecked()
    expect(confirm).toBeDisabled()

    await user.click(screen.getByRole('radio', { name: /Mel/ }))

    expect(confirm).toBeEnabled()
    await user.click(confirm)
    await screen.findByRole('heading', { name: 'Agendamento confirmado' })
    expect((calls(all, 'POST', '/api/appointments')[0].body as { petId: string }).petId).toBe('pet-2')
  })

  it('sem nenhum pet: cadastra na hora, o pet novo já fica escolhido e a reserva o usa', async () => {
    let listed: unknown[] = []
    const { calls: all, user } = await openBook({
      'GET /api/customer/pets': () => json(200, listed),
      'POST /api/customer/pets': () => {
        listed = [REX]
        return json(201, REX)
      },
      'POST /api/appointments': () => json(201, booked),
    })
    expect(await screen.findByText('Cadastre o pet que será atendido.')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Confirmar agendamento' })).toBeDisabled()
    await fillNewPet(user, { name: 'Rex', species: 'DOG' })
    await user.click(screen.getByRole('button', { name: 'Cadastrar e escolher' }))

    await waitFor(() => expect(screen.getByRole('radio', { name: /Rex/ })).toBeChecked())
    expect(screen.getByRole('button', { name: 'Confirmar agendamento' })).toBeEnabled()
    await user.click(screen.getByRole('button', { name: 'Confirmar agendamento' }))
    await screen.findByRole('heading', { name: 'Agendamento confirmado' })
    expect((calls(all, 'POST', '/api/appointments')[0].body as { petId: string }).petId).toBe('pet-1')
  })

  it('falha ao carregar os pets mostra o erro e "Tentar de novo" recarrega', async () => {
    let fail = true
    const { user } = await openBook({ 'GET /api/customer/pets': () => (fail ? problem(500, 'erro') : json(200, [REX])) })
    expect(await screen.findByRole('alert')).toHaveTextContent('Algo deu errado no servidor')
    expect(screen.queryByRole('button', { name: 'Confirmar agendamento' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Confirmar agendamento' })).toBeDisabled()
    fail = false

    await user.click(screen.getByRole('button', { name: 'Tentar de novo' }))

    expect(await screen.findByRole('radio', { name: /Rex/ })).toBeChecked()
  })

  const KIKA = { id: 'pet-4', name: 'Kika', species: 'CAT', breed: null, size: null, notes: null, active: true }

  it.each([
    ['PET_INACTIVE', 422, 'Esse pet está arquivado. Reative-o em Meus pets ou escolha outro.'],
    ['PET_NOT_FOUND', 404, 'Não encontramos esse pet. Atualize a lista e escolha de novo.'],
  ])('recusa %s: mostra o motivo, mantém o horário, limpa a escolha e refaz a lista de pets', async (reason, status, message) => {
    let listed = [REX, MEL, KIKA]
    const { user } = await openBook({
      'GET /api/customer/pets': () => json(200, listed),
      'POST /api/appointments': () => {
        listed = [MEL, KIKA]
        return problem(status, 'agendamento recusado', { reason })
      },
    })
    await user.click(await screen.findByRole('radio', { name: /Rex/ }))
    await user.click(screen.getByRole('button', { name: 'Confirmar agendamento' }))

    expect(await screen.findByText(message)).toBeInTheDocument()
    await waitFor(() => expect(screen.queryByRole('radio', { name: /Rex/ })).not.toBeInTheDocument())
    expect(screen.getByRole('radio', { name: /Mel/ })).not.toBeChecked() // a escolha foi limpa
    expect(screen.getByRole('button', { name: '09:00' })).toHaveAttribute('aria-pressed', 'true') // o horário continua escolhido
    expect(screen.getByRole('button', { name: 'Confirmar agendamento' })).toBeDisabled()
  })

  it('se a lista de pets não conseguir ser atualizada depois da recusa, o único pet NÃO volta escolhido e a confirmação fica bloqueada até uma lista nova', async () => {
    let phase: 'before' | 'broken' | 'fixed' = 'before'
    const { user } = await openBook({
      'GET /api/customer/pets': () => (phase === 'before' ? json(200, [REX]) : phase === 'broken' ? problem(500, 'erro') : json(200, [MEL])),
      'POST /api/appointments': () => {
        phase = 'broken'
        return problem(422, 'agendamento recusado', { reason: 'PET_INACTIVE' })
      },
    })
    expect(await screen.findByRole('radio', { name: /Rex/ })).toBeChecked() // único pet: vinha escolhido
    await user.click(screen.getByRole('button', { name: 'Confirmar agendamento' }))

    expect(await screen.findByText('Esse pet está arquivado. Reative-o em Meus pets ou escolha outro.')).toBeInTheDocument()
    // a atualização falhou: os dados antigos continuam na tela, com aviso, mas o pet recusado não pode estar escolhido
    expect(await screen.findByText(/Não foi possível atualizar os dados/)).toBeInTheDocument()
    expect(screen.getByRole('radio', { name: /Rex/ })).not.toBeChecked()
    expect(screen.getByRole('radio', { name: /Rex/ })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Confirmar agendamento' })).toBeDisabled()

    phase = 'fixed'
    await user.click(screen.getAllByRole('button', { name: 'Tentar de novo' })[0])

    // lista nova: o único pet agora é a Mel, que vem escolhida, e a confirmação libera
    expect(await screen.findByRole('radio', { name: /Mel/ })).toBeChecked()
    expect(screen.queryByRole('radio', { name: /Rex/ })).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Confirmar agendamento' })).toBeEnabled()
  })

  it('durante o envio as escolhas de pet ficam bloqueadas', async () => {
    const pending = deferred<Response>()
    const { user } = await openBook({ 'GET /api/customer/pets': () => json(200, [REX, MEL]), 'POST /api/appointments': () => pending.promise })
    await user.click(await screen.findByRole('radio', { name: /Rex/ }))
    await user.click(screen.getByRole('button', { name: 'Confirmar agendamento' }))

    expect(screen.getByRole('radio', { name: /Rex/ })).toBeDisabled()
    expect(screen.getByRole('radio', { name: /Mel/ })).toBeDisabled()
    await act(async () => pending.resolve(json(201, booked)))
  })
})

describe('o pet nas listas', () => {
  const agendaItem = (id: string, pet: unknown) => ({
    id,
    startsAt: `${today}T15:00:00Z`,
    endsAt: `${today}T16:00:00Z`,
    status: 'SCHEDULED',
    serviceId: 's-1',
    serviceName: 'Banho e tosa',
    customerId: 'c-1',
    customerName: 'Maria Souza',
    pet,
  })

  it('a agenda do dia mostra o pet e as observações do tutor; agendamento sem pet não mostra nada a mais', async () => {
    const { user } = await loginAs('ATTENDANT', {
      [`GET /api/agenda?date=${today}`]: () =>
        json(200, [agendaItem('a-1', { name: 'Rex', species: 'DOG', breed: 'Poodle', size: 'MEDIUM', notes: 'Tem coceira na pata.' }), agendaItem('a-2', null)]),
    })
    await user.click(within(screen.getByRole('navigation')).getByRole('link', { name: 'Agenda' }))
    const rows = await screen.findAllByRole('listitem')
    expect(rows[0]).toHaveTextContent('Rex · Cachorro · Poodle · Médio')
    expect(rows[0]).toHaveTextContent('Observações do tutor: Tem coceira na pata.')
    expect(rows[1]).not.toHaveTextContent('Cachorro')
    expect(rows[1]).not.toHaveTextContent('Observações do tutor')
  })

  it('a fila mostra o pet de quem já chegou', async () => {
    const { user } = await loginAs('ATTENDANT', {
      'GET /api/agenda/queue': () =>
        json(200, [{ ...agendaItem('a-1', { name: 'Mel', species: 'CAT', breed: null, size: 'SMALL', notes: null }), position: 1, checkedInAt: `${today}T14:50:00Z` }]),
    })
    await user.click(within(screen.getByRole('navigation')).getByRole('link', { name: 'Fila' }))
    const row = (await screen.findAllByRole('listitem'))[0]
    expect(row).toHaveTextContent('Mel · Gato · Pequeno')
    expect(row).not.toHaveTextContent('Observações do tutor')
  })

  it('"Meus agendamentos" mostra o nome e a espécie do pet; sem pet, a linha continua igual', async () => {
    const item = (id: string, pet: unknown) => ({ id, providerId: 'p-1', serviceId: 's-1', startsAt: '2026-12-10T13:00:00Z', endsAt: '2026-12-10T14:00:00Z', status: 'SCHEDULED', businessName: 'Estúdio', timeZone: ZONE, serviceName: 'Banho', pet })
    const { user } = await loginAs('CUSTOMER', {
      'GET /api/appointments/mine?page=0&size=50': () => json(200, [item('a-1', { name: 'Rex', species: 'DOG' }), item('a-2', null)]),
    })
    await user.click(within(screen.getByRole('navigation', { name: 'Menu do cliente' })).getByRole('link', { name: 'Meus agendamentos' }))
    const rows = await screen.findAllByRole('listitem')
    expect(rows[0]).toHaveTextContent('Banho em Estúdio · Rex (Cachorro)')
    expect(rows[1]).not.toHaveTextContent('Cachorro')
    expect(addDays(today, 0)).toBe(today)
  })
})
