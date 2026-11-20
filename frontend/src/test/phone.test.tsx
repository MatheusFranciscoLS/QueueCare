import { act, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { formatPhone, validatePhone, whatsappLink } from '../api/phone'
import { json, mockApi, PASSWORD, problem, renderWithHarness, token, USER } from './helpers'

vi.mock('../pages/TodaySummary', () => ({ TodaySummary: () => null }))
afterEach(() => vi.unstubAllGlobals())

describe('telefone (funções)', () => {
  it('formata o número da API e deixa passar o que não tem o formato', () => {
    expect(formatPhone('5511912345678')).toBe('(11) 91234-5678')
    expect(formatPhone('551132345678')).toBe('(11) 3234-5678')
    expect(formatPhone('123')).toBe('123')
    expect(formatPhone(null)).toBe('')
  })

  it('confere o telefone como a API: com máscara, com ou sem DDI', () => {
    for (const ok of ['(11) 91234-5678', '11912345678', '+55 11 91234-5678', '5511912345678', '(11) 3234-5678']) expect(validatePhone(ok), ok).toBeUndefined()
    for (const bad of ['', '123', '(01) 91234-5678', '(11) 81234-5678', 'abc', '+11912345678']) expect(validatePhone(bad), bad).toBeDefined()
  })

  it('link do WhatsApp com mensagem opcional e codificada', () => {
    expect(whatsappLink('5511912345678')).toBe('https://wa.me/5511912345678')
    expect(whatsappLink('5511912345678', 'Oi, o Thor está pronto!')).toBe('https://wa.me/5511912345678?text=Oi%2C%20o%20Thor%20est%C3%A1%20pronto!')
  })
})

async function openProfile(me: Record<string, unknown>, routes: Parameters<typeof mockApi>[0] = {}) {
  const { calls } = mockApi({ 'POST /api/auth/login': () => token(), 'GET /api/me': () => json(200, me), ...routes })
  const view = renderWithHarness()
  await act(async () => view.api().login('x@exemplo.test', PASSWORD))
  await screen.findByRole('heading', { name: new RegExp(`Olá, ${String(me.name)}`) })
  const user = userEvent.setup()
  await user.click(screen.getByRole('link', { name: 'Meu perfil' }))
  await screen.findByRole('heading', { name: 'Meu perfil' })
  return { calls, user }
}

describe('Meu perfil', () => {
  it('mostra os dados, formata o WhatsApp e salva o novo número', async () => {
    const { calls, user } = await openProfile({ ...USER, phone: '5511912345678' }, {
      'PUT /api/me/phone': ({ body }) => json(200, { ...USER, phone: String((body as { phone: string }).phone).replace(/\D/g, '').replace(/^(\d{10,11})$/, '55$1') }),
    })
    expect(screen.getByText('maria@exemplo.test')).toBeInTheDocument()
    const field = screen.getByLabelText('WhatsApp')
    expect(field).toHaveValue('(11) 91234-5678')
    await user.clear(field)
    await user.type(field, '21 98888-7777')
    await user.click(screen.getByRole('button', { name: 'Salvar contato' }))
    expect(await screen.findByText('Contato salvo.')).toBeVisible()
    expect(calls.find((c) => c.path === '/api/me/phone')?.body).toEqual({ phone: '21 98888-7777' })
    expect(screen.getByLabelText('WhatsApp')).toHaveValue('(21) 98888-7777')
  })

  it('cliente não salva sem número; telefone inválido da API aparece no campo', async () => {
    const { calls, user } = await openProfile({ ...USER, phone: null }, {
      'PUT /api/me/phone': () => problem(400, 'dados inválidos', { errors: { phone: 'informe DDD e número' } }),
    })
    await user.click(screen.getByRole('button', { name: 'Salvar contato' }))
    expect(await screen.findByText('Informe o seu WhatsApp, com DDD.')).toBeInTheDocument()
    expect(calls.some((c) => c.path === '/api/me/phone')).toBe(false)
    await user.type(screen.getByLabelText('WhatsApp'), '11912345678')
    await user.click(screen.getByRole('button', { name: 'Salvar contato' }))
    expect(await screen.findByText('informe DDD e número')).toBeInTheDocument()
  })

  it('o Início do cliente avisa quando falta o WhatsApp e leva ao perfil', async () => {
    mockApi({ 'POST /api/auth/login': () => token(), 'GET /api/me': () => json(200, { ...USER, phone: null }) })
    const view = renderWithHarness()
    await act(async () => view.api().login('x@exemplo.test', PASSWORD))
    const notice = await screen.findByText(/Falta o seu WhatsApp/)
    expect(within(notice.closest('p') as HTMLElement).getByRole('link', { name: 'Adicionar WhatsApp' })).toHaveAttribute('href', '/perfil')
  })

  it('com WhatsApp cadastrado o aviso não aparece', async () => {
    mockApi({ 'POST /api/auth/login': () => token(), 'GET /api/me': () => json(200, { ...USER, phone: '5511912345678' }) })
    const view = renderWithHarness()
    await act(async () => view.api().login('x@exemplo.test', PASSWORD))
    await screen.findByRole('heading', { name: 'Olá, Maria Souza' })
    expect(screen.queryByText(/Falta o seu WhatsApp/)).not.toBeInTheDocument()
  })
})

describe('WhatsApp na agenda do dia e na fila', () => {
  const WORKPLACE = { providerId: 'p-1', businessName: 'Estúdio Bem-te-vi', timeZone: 'America/Sao_Paulo' }
  const item = (extra: Record<string, unknown>) => ({
    id: 'a-1',
    startsAt: '2027-03-02T13:00:00Z',
    endsAt: '2027-03-02T14:00:00Z',
    status: 'SCHEDULED',
    serviceId: 's-1',
    serviceName: 'Banho',
    customerId: 'c-1',
    customerName: 'Marina Souza',
    ...extra,
  })

  it('a linha do dia mostra o número formatado como link do WhatsApp, só quando o tutor informou', async () => {
    const today = (await import('../api/dates')).todayIn(WORKPLACE.timeZone)
    mockApi({
      'POST /api/auth/login': () => token(),
      'GET /api/me': () => json(200, { ...USER, role: 'PROVIDER', name: 'Helena Prado' }),
      'GET /api/agenda/workplace': () => json(200, WORKPLACE),
      [`GET /api/agenda?date=${today}`]: () => json(200, [item({ customerPhone: '5511912345678' }), item({ id: 'a-2', customerName: 'Carla Mendes', customerPhone: null })]),
    })
    const view = renderWithHarness()
    await act(async () => view.api().login('x@exemplo.test', PASSWORD))
    await screen.findByRole('heading', { name: 'Olá, Helena Prado' })
    const user = userEvent.setup()
    await user.click(within(screen.getByRole('navigation', { name: 'Menu do prestador' })).getByRole('link', { name: 'Agenda' }))
    const link = await screen.findByRole('link', { name: /\(11\) 91234-5678/ })
    expect(link).toHaveAttribute('href', 'https://wa.me/5511912345678')
    expect(link).toHaveAttribute('target', '_blank')
    expect(link).toHaveAttribute('rel', expect.stringContaining('noopener'))
    expect(screen.getAllByRole('link', { name: /WhatsApp/ })).toHaveLength(1) // a Carla não informou: sem link
  })
})
