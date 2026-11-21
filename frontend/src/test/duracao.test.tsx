import { act, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { todayIn } from '../api/dates'
import { durationForPet, durationLine, durationSummary } from '../api/duration'
import { readDurations } from '../pages/provider/durationText'
import { json, mockApi, PASSWORD, problem, renderWithHarness, token, USER, type Call } from './helpers'

vi.mock('../pages/TodaySummary', () => ({ TodaySummary: () => null }))
afterEach(() => vi.unstubAllGlobals())

type Routes = Record<string, (call: Call) => Response | Promise<Response>>

const ZONE = 'America/Sao_Paulo'
const today = todayIn(ZONE)
const BANHO = { durationMinutes: 60, durationMediumMinutes: 90, durationLargeMinutes: 120, coatExtraMinutes: 30 }

describe('duração por porte e pelo (funções)', () => {
  it('a duração do pet segue a regra da API: porte, mais o extra de pelo longo, cacheado ou duplo', () => {
    expect(durationForPet(BANHO, 'SMALL', 'SHORT')).toBe(60)
    expect(durationForPet(BANHO, null, null)).toBe(60)
    expect(durationForPet(BANHO, 'MEDIUM', 'SHORT')).toBe(90)
    expect(durationForPet(BANHO, 'LARGE', 'MEDIUM')).toBe(120) // pelo médio não soma
    expect(durationForPet(BANHO, 'SMALL', 'CURLY')).toBe(90)
    expect(durationForPet(BANHO, 'MEDIUM', 'DOUBLE')).toBe(120)
    expect(durationForPet(BANHO, 'LARGE', 'LONG')).toBe(150)
    expect(durationForPet({ durationMinutes: 45 }, 'LARGE', 'LONG')).toBe(45) // sem os campos novos, a base
    expect(durationForPet({ durationMinutes: 45, durationLargeMinutes: null }, 'LARGE', null)).toBe(45)
  })

  it('o resumo do serviço diz "a partir de" quando a duração varia', () => {
    expect(durationSummary({ durationMinutes: 45 })).toBe('45 min')
    expect(durationSummary({ durationMinutes: 45, coatExtraMinutes: 0 })).toBe('45 min')
    expect(durationSummary(BANHO)).toBe('a partir de 60 min')
    expect(durationSummary({ durationMinutes: 60, coatExtraMinutes: 15 })).toBe('a partir de 60 min')
    expect(durationSummary({ durationMinutes: 60, durationMediumMinutes: 45 })).toBe('a partir de 45 min')
  })

  it('a linha do estúdio mostra a duração por porte e o extra de pelo', () => {
    expect(durationLine({ durationMinutes: 45 })).toBe('45 min')
    expect(durationLine(BANHO)).toBe('P 60 · M 90 · G 120 min · +30 min para pelo longo, cacheado ou duplo')
    expect(durationLine({ durationMinutes: 60, durationLargeMinutes: 100 })).toBe('P 60 · M 60 · G 100 min')
  })

  it('lê os campos: vazio é "usa a base" e "nenhum"; fora da faixa ou pior caso acima do teto é erro por campo', () => {
    expect(readDurations({ medium: '', large: '', coatExtra: '' }, 60).durations).toEqual({ durationMediumMinutes: null, durationLargeMinutes: null, coatExtraMinutes: 0 })
    expect(readDurations({ medium: '90', large: '120', coatExtra: '30' }, 60).durations).toEqual({ durationMediumMinutes: 90, durationLargeMinutes: 120, coatExtraMinutes: 30 })
    expect(readDurations({ medium: '4', large: '', coatExtra: '' }, 60).errors.durationMedium).toBeTruthy()
    expect(readDurations({ medium: '', large: '481', coatExtra: '' }, 60).errors.durationLarge).toBeTruthy()
    expect(readDurations({ medium: '', large: '', coatExtra: '241' }, 60).errors.coatExtra).toBeTruthy()
    expect(readDurations({ medium: '', large: '', coatExtra: '-1' }, 60).errors.coatExtra).toBeTruthy()
    expect(readDurations({ medium: '', large: '', coatExtra: '1,5' }, 60).errors.coatExtra).toBeTruthy()
    expect(readDurations({ medium: '', large: '400', coatExtra: '100' }, 60).errors.coatExtra).toMatch(/480/)
  })
})

async function loginAs(role: string, routes: Routes) {
  const { calls } = mockApi({
    'POST /api/auth/login': () => token(),
    'GET /api/me': () => json(200, { ...USER, role, name: 'Maria Souza', phone: '5511912345678' }),
    'GET /api/agenda/workplace': () => json(200, { providerId: 'p-1', businessName: 'Estúdio', timeZone: ZONE }),
    ...routes,
  })
  const view = renderWithHarness()
  await act(async () => view.api().login('x@exemplo.test', PASSWORD))
  await screen.findByRole('heading', { name: 'Olá, Maria Souza' })
  return { calls, user: userEvent.setup() }
}

describe('duração no cadastro do serviço', () => {
  const open = async (routes: Routes) => {
    const ctx = await loginAs('PROVIDER', { 'GET /api/provider': () => json(200, { id: 'p-1', businessName: 'Estúdio', timeZone: ZONE, minimumNoticeMinutes: 0, cancellationDeadlineMinutes: 60 }), ...routes })
    await ctx.user.click(within(screen.getByRole('navigation', { name: 'Menu do prestador' })).getByRole('link', { name: 'Serviços' }))
    await screen.findByRole('heading', { name: 'Serviços' })
    return ctx
  }

  it('envia a duração por porte e o extra de pelo, e a linha do serviço mostra os tempos', async () => {
    let services: unknown[] = []
    const { calls, user } = await open({
      'GET /api/provider/services': () => json(200, services),
      'POST /api/provider/services': ({ body }) => {
        services = [{ id: 's-1', active: true, ...(body as object) }]
        return json(201, services[0])
      },
    })
    await user.type(await screen.findByLabelText('Nome do serviço'), 'Banho')
    await user.type(screen.getByLabelText('Duração para porte médio (minutos, opcional)'), '90')
    await user.type(screen.getByLabelText('Duração para porte grande (minutos, opcional)'), '120')
    await user.type(screen.getByLabelText('Minutos a mais para pelo longo, cacheado ou duplo (opcional)'), '30')
    await user.click(screen.getByRole('button', { name: 'Adicionar serviço' }))

    expect(await screen.findByText(/P 60 · M 90 · G 120 min · \+30 min para pelo longo, cacheado ou duplo/)).toBeInTheDocument()
    expect(calls.find((c) => c.method === 'POST' && c.path === '/api/provider/services')?.body).toMatchObject({
      name: 'Banho',
      durationMinutes: 60,
      durationMediumMinutes: 90,
      durationLargeMinutes: 120,
      coatExtraMinutes: 30,
    })
  })

  it('valor fora da faixa não é enviado e mostra o erro no campo; o erro da API também', async () => {
    const { calls, user } = await open({
      'GET /api/provider/services': () => json(200, []),
      'POST /api/provider/services': () => problem(400, 'dados inválidos', { errors: { coatExtraMinutes: 'a maior duração somada ao extra de pelo não pode passar de 480 minutos' } }),
    })
    await user.type(await screen.findByLabelText('Nome do serviço'), 'Banho')
    await user.type(screen.getByLabelText('Duração para porte grande (minutos, opcional)'), '3')
    await user.click(screen.getByRole('button', { name: 'Adicionar serviço' }))
    expect(await screen.findByText('Informe de 5 a 480 minutos, ou deixe vazio.')).toBeInTheDocument()
    expect(calls.some((c) => c.method === 'POST' && c.path === '/api/provider/services')).toBe(false)

    await user.clear(screen.getByLabelText('Duração para porte grande (minutos, opcional)'))
    await user.type(screen.getByLabelText('Duração para porte grande (minutos, opcional)'), '120')
    await user.type(screen.getByLabelText('Minutos a mais para pelo longo, cacheado ou duplo (opcional)'), '30')
    await user.click(screen.getByRole('button', { name: 'Adicionar serviço' }))
    expect(await screen.findByText(/não pode passar de 480 minutos/)).toBeInTheDocument()
  })

  it('editar traz as durações atuais e salvar envia as novas', async () => {
    const { calls, user } = await open({
      'GET /api/provider/services': () => json(200, [{ id: 's-1', name: 'Banho', active: true, ...BANHO }]),
      'PUT /api/provider/services/s-1': ({ body }) => json(200, { id: 's-1', ...(body as object) }),
    })
    await user.click(await screen.findByRole('button', { name: 'Editar' }))
    const large = screen.getAllByLabelText('Duração para porte grande (minutos, opcional)').find((el) => (el as HTMLInputElement).value === '120') as HTMLInputElement
    expect(large).toBeTruthy()
    await user.clear(large)
    await user.type(large, '150')
    await user.click(screen.getByRole('button', { name: 'Salvar' }))

    await screen.findByRole('button', { name: 'Editar' })
    expect(calls.find((c) => c.method === 'PUT')?.body).toMatchObject({ durationMinutes: 60, durationMediumMinutes: 90, durationLargeMinutes: 150, coatExtraMinutes: 30, active: true })
  })
})

describe('duração no Agendar', () => {
  const PROVIDERS = [{ id: 'p-1', businessName: 'Estúdio Pelo e Ponto', timeZone: ZONE }]
  const SERVICES = [{ id: 's-1', name: 'Banho', ...BANHO }]
  const THOR = { id: 'pet-9', name: 'Thor', species: 'DOG', breed: 'Golden', size: 'LARGE', coat: 'LONG', notes: null, active: true }
  const slot = { localStart: today + 'T09:00:00', startsAt: today + 'T12:00:00Z', endsAt: today + 'T14:30:00Z' }

  it('o tutor vê "a partir de", e depois do pet e do horário a confirmação mostra a duração exata; os horários pedem o pet', async () => {
    const { calls, user } = await loginAs('CUSTOMER', {
      'GET /api/catalog/providers?page=0&size=50': () => json(200, PROVIDERS),
      'GET /api/catalog/providers/p-1/services': () => json(200, SERVICES),
      'GET /api/customer/pets': () => json(200, [THOR]),
      ['GET /api/providers/p-1/slots?serviceId=s-1&date=' + today + '&petId=pet-9']: () => json(200, [slot]),
    })
    await user.click(within(screen.getByRole('navigation', { name: 'Menu do cliente' })).getByRole('link', { name: 'Agendar' }))
    await user.click(await screen.findByRole('radio', { name: 'Estúdio Pelo e Ponto' }))
    expect(await screen.findByRole('radio', { name: /Banho · a partir de 60 min/ })).toBeInTheDocument()
    await user.click(screen.getByRole('radio', { name: /Banho/ }))

    // O pet é o passo 3 e já vem escolhido (único); só depois aparecem o dia (4) e o horário (5).
    expect(await screen.findByText('3. Escolha o pet')).toBeInTheDocument()
    expect(await screen.findByText('4. Escolha o dia')).toBeInTheDocument()
    expect(screen.getByText('5. Escolha o horário')).toBeInTheDocument()
    await user.click(await screen.findByRole('button', { name: '09:00' }))

    expect(screen.getByRole('region', { name: 'Confirmar agendamento' })).toHaveTextContent('Banho (150 min) para Thor')
    expect(calls.some((c) => c.path === '/api/providers/p-1/slots?serviceId=s-1&date=' + today + '&petId=pet-9')).toBe(true)
  })
})
