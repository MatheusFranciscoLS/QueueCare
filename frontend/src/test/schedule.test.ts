import { describe, expect, it } from 'vitest'
import { countWindows, emptySchedule, fromWindows, MAX_WINDOWS, toHourMinute, toWindows, validateSchedule } from '../api/schedule'

describe('expediente: conversões', () => {
  it('a hora da API (08:00:00) vira HH:mm e volta ordenada por dia e início', () => {
    expect(toHourMinute('08:00:00')).toBe('08:00')
    const schedule = fromWindows([
      { dayOfWeek: 'TUESDAY', start: '14:00:00', end: '18:00:00' },
      { dayOfWeek: 'MONDAY', start: '14:00:00', end: '18:00:00' },
      { dayOfWeek: 'MONDAY', start: '08:00:00', end: '12:00:00' },
    ])

    expect(schedule.MONDAY).toEqual([{ start: '08:00', end: '12:00' }, { start: '14:00', end: '18:00' }])
    expect(toWindows(schedule)).toEqual([
      { dayOfWeek: 'MONDAY', start: '08:00', end: '12:00' },
      { dayOfWeek: 'MONDAY', start: '14:00', end: '18:00' },
      { dayOfWeek: 'TUESDAY', start: '14:00', end: '18:00' },
    ])
    expect(countWindows(schedule)).toBe(3)
  })

  it('expediente vazio não envia janela nenhuma (a API trata como "não atende")', () => {
    expect(toWindows(emptySchedule())).toEqual([])
  })
})

describe('expediente: validação (a API confere de novo)', () => {
  const day = (slots: { start: string; end: string }[]) => ({ ...emptySchedule(), MONDAY: slots })

  it('aceita horários válidos, inclusive colados (o fim de um é o início do outro)', () => {
    const result = validateSchedule(day([{ start: '08:00', end: '12:00' }, { start: '12:00', end: '18:00' }]))
    expect(result.perDay).toEqual({})
    expect(result.total).toBeUndefined()
  })

  it.each([
    ['início depois do fim', [{ start: '12:00', end: '08:00' }], /antes do fim/],
    ['início igual ao fim', [{ start: '08:00', end: '08:00' }], /antes do fim/],
    ['campo vazio', [{ start: '', end: '08:00' }], /Preencha/],
    ['hora inválida', [{ start: '25:00', end: '26:00' }], /Preencha/],
    ['sobreposição', [{ start: '08:00', end: '12:00' }, { start: '11:00', end: '13:00' }], /sobrepõem/],
    ['sobreposição fora de ordem', [{ start: '14:00', end: '18:00' }, { start: '08:00', end: '15:00' }], /sobrepõem/],
    ['um dentro do outro', [{ start: '08:00', end: '18:00' }, { start: '10:00', end: '11:00' }], /sobrepõem/],
  ])('recusa: %s', (_name, slots, message) => {
    expect(validateSchedule(day(slots)).perDay.MONDAY).toMatch(message)
  })

  it('o mesmo horário em dias diferentes não é sobreposição', () => {
    const schedule = { ...emptySchedule(), MONDAY: [{ start: '08:00', end: '12:00' }], TUESDAY: [{ start: '08:00', end: '12:00' }] }
    expect(validateSchedule(schedule).perDay).toEqual({})
  })

  it('limita o total de horários como a API', () => {
    const many = { ...emptySchedule() }
    many.MONDAY = Array.from({ length: MAX_WINDOWS + 1 }, (_, i) => ({ start: `${String(Math.floor(i / 6)).padStart(2, '0')}:${String((i % 6) * 10).padStart(2, '0')}`, end: `${String(Math.floor(i / 6)).padStart(2, '0')}:${String((i % 6) * 10 + 5).padStart(2, '0')}` }))
    expect(validateSchedule(many).total).toMatch(/No máximo 70/)
  })
})
