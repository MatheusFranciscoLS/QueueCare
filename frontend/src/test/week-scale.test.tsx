import { act, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { startOfWeek, todayIn } from '../api/dates'
import { BASE_HOUR_PX, blockBox, GAP_PX, hourHeight, MAX_HOUR_PX, MIN_BLOCK_PX } from '../pages/agenda/weekScale'
import { json, mockApi, PASSWORD, renderWithHarness, token, USER } from './helpers'

vi.mock('../pages/TodaySummary', () => ({ TodaySummary: () => null }))
afterEach(() => vi.unstubAllGlobals())

describe('escala da grade da semana', () => {
  it('sem atendimentos, ou só com atendimentos longos, usa a altura-base da hora', () => {
    expect(hourHeight([])).toBe(BASE_HOUR_PX)
    expect(hourHeight([60, 90, 45])).toBe(BASE_HOUR_PX)
  })

  it('com atendimento curto, a hora cresce para o MENOR bloco ter pelo menos o mínimo, sem passar do limite', () => {
    const h30 = hourHeight([30, 60])
    expect(blockBox(0, 30, h30).height).toBeGreaterThanOrEqual(MIN_BLOCK_PX)
    const h15 = hourHeight([15, 60])
    expect(h15).toBeGreaterThan(h30)
    expect(blockBox(0, 15, h15).height).toBeGreaterThanOrEqual(MIN_BLOCK_PX) // até o de 15 minutos é um alvo de toque de 44 px
    expect(MIN_BLOCK_PX).toBeGreaterThanOrEqual(44)
    expect(hourHeight([1])).toBe(MAX_HOUR_PX)
  })

  it('a escala é proporcional: o de 60 minutos tem o dobro do de 30, descontada a folga', () => {
    const h = hourHeight([30])
    const a = blockBox(0, 30, h).height + GAP_PX
    const b = blockBox(0, 60, h).height + GAP_PX
    expect(b).toBeCloseTo(a * 2, 5)
  })

  it('dois atendimentos SEGUIDOS (15 minutos cada) nunca se sobrepõem nem se encostam', () => {
    const h = hourHeight([15, 15])
    const first = blockBox(0, 15, h)
    const second = blockBox(15, 15, h)
    expect(second.top - (first.top + first.height)).toBeGreaterThanOrEqual(GAP_PX)
    const c1 = blockBox(30, 30, h)
    const c2 = blockBox(60, 30, h)
    expect(c2.top - (c1.top + c1.height)).toBeGreaterThanOrEqual(GAP_PX)
  })
})

describe('semana com atendimentos curtos', () => {
  const zone = 'America/Sao_Paulo'
  const monday = startOfWeek(todayIn(zone))
  const item = (id: string, hourZ: string, minutes: number, name: string) => {
    const start = new Date(`${monday}T${hourZ}:00Z`)
    return {
      id,
      startsAt: start.toISOString(),
      endsAt: new Date(start.getTime() + minutes * 60_000).toISOString(),
      status: 'SCHEDULED',
      serviceId: 's',
      serviceName: 'Banho',
      customerId: 'c',
      customerName: name,
    }
  }

  it('blocos de 15 e 30 minutos ficam marcados como curtos, e a hora da grade cresce para eles caberem', async () => {
    mockApi({
      'POST /api/auth/login': () => token(),
      'GET /api/me': () => json(200, { ...USER, role: 'PROVIDER', name: 'Helena Prado' }),
      'GET /api/agenda/workplace': () => json(200, { providerId: 'p', businessName: 'Estúdio', timeZone: zone }),
      [`GET /api/agenda/week?start=${monday}`]: () => json(200, [item('a', '12:00', 15, 'Ana'), item('b', '12:15', 15, 'Beto'), item('c', '13:00', 30, 'Caio'), item('d', '14:00', 60, 'Duda')]),
    })
    const view = renderWithHarness()
    await act(async () => view.api().login('x@exemplo.test', PASSWORD))
    await screen.findByRole('heading', { name: 'Olá, Helena Prado' })
    const user = userEvent.setup()
    await user.click(within(screen.getByRole('navigation', { name: 'Menu do prestador' })).getByRole('link', { name: 'Agenda' }))
    await user.click(await screen.findByRole('button', { name: 'Semana' }))

    const week = await screen.findByRole('region', { name: /^Semana de / })
    expect(week.style.getPropertyValue('--h')).toBe(`${hourHeight([15, 15, 30, 60])}px`)
    const segunda = within(week).getAllByRole('region')[0]
    const blocks = [...segunda.querySelectorAll('.week-block')]
    expect(blocks.map((b) => b.getAttribute('data-short'))).toEqual(['true', 'true', 'true', null])
    const starts = blocks.map((b) => Number((b as HTMLElement).style.getPropertyValue('--start')))
    expect(starts[1] - starts[0]).toBe(15) // o segundo começa 15 minutos depois do primeiro: posição temporal real
  })
})
