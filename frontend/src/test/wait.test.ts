import { describe, expect, it } from 'vitest'
import { waitLabel } from '../api/wait'

const at = (iso: string) => new Date(iso)

describe('waitLabel', () => {
  it.each([
    ['2026-11-16T12:00:30Z', '2026-11-16T12:00:00Z', 'espera de menos de 1 min'],
    ['2026-11-16T12:01:00Z', '2026-11-16T12:00:00Z', 'espera de 1 min'],
    ['2026-11-16T12:59:59Z', '2026-11-16T12:00:00Z', 'espera de 59 min'],
    ['2026-11-16T13:00:00Z', '2026-11-16T12:00:00Z', 'espera de 1 h'],
    ['2026-11-16T13:05:00Z', '2026-11-16T12:00:00Z', 'espera de 1 h 05'],
    ['2026-11-16T14:30:00Z', '2026-11-16T12:00:00Z', 'espera de 2 h 30'],
  ])('agora %s, chegou %s: %s', (now, arrived, expected) => {
    expect(waitLabel(arrived, at(now))).toBe(expected)
  })

  it('relógio do aparelho adiantado em relação à chegada nunca vira negativo', () => {
    expect(waitLabel('2026-11-16T12:10:00Z', at('2026-11-16T12:00:00Z'))).toBe('espera de menos de 1 min')
  })

  it('chegada ilegível não quebra', () => {
    expect(waitLabel('xx', at('2026-11-16T12:00:00Z'))).toBe('espera de menos de 1 min')
  })
})
