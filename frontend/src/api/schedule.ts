import type { AvailabilityWindow, DayOfWeek } from './provider'

export const DAYS: { key: DayOfWeek; label: string }[] = [
  { key: 'MONDAY', label: 'Segunda-feira' },
  { key: 'TUESDAY', label: 'Terça-feira' },
  { key: 'WEDNESDAY', label: 'Quarta-feira' },
  { key: 'THURSDAY', label: 'Quinta-feira' },
  { key: 'FRIDAY', label: 'Sexta-feira' },
  { key: 'SATURDAY', label: 'Sábado' },
  { key: 'SUNDAY', label: 'Domingo' },
]

export interface Slot {
  start: string
  end: string
}

export type Schedule = Record<DayOfWeek, Slot[]>

/** Mesmo limite da API (ProviderAdminService.MAX_WINDOWS). */
export const MAX_WINDOWS = 70

export function emptySchedule(): Schedule {
  return { MONDAY: [], TUESDAY: [], WEDNESDAY: [], THURSDAY: [], FRIDAY: [], SATURDAY: [], SUNDAY: [] }
}

/** "08:00:00" -> "08:00" (o campo de hora do navegador trabalha com HH:mm). */
export function toHourMinute(time: string): string {
  return time.slice(0, 5)
}

export function fromWindows(windows: AvailabilityWindow[]): Schedule {
  const schedule = emptySchedule()
  for (const w of windows) schedule[w.dayOfWeek].push({ start: toHourMinute(w.start), end: toHourMinute(w.end) })
  for (const day of DAYS) schedule[day.key].sort((a, b) => a.start.localeCompare(b.start))
  return schedule
}

/** Conjunto COMPLETO a enviar (a API substitui tudo): ordenado por dia e por início. */
export function toWindows(schedule: Schedule): AvailabilityWindow[] {
  return DAYS.flatMap((day) =>
    [...schedule[day.key]].sort((a, b) => a.start.localeCompare(b.start)).map((s) => ({ dayOfWeek: day.key, start: s.start, end: s.end })),
  )
}

export function countWindows(schedule: Schedule): number {
  return DAYS.reduce((sum, day) => sum + schedule[day.key].length, 0)
}

const HOUR_MINUTE = /^([01]\d|2[0-3]):[0-5]\d$/

/**
 * Confere o expediente antes de enviar (a API confere de novo): horários preenchidos, início antes do fim e sem sobreposição no
 * mesmo dia. Janelas coladas (o fim de uma é o início da outra) são aceitas, como na API. Devolve mensagens por dia.
 */
export function validateSchedule(schedule: Schedule): { perDay: Partial<Record<DayOfWeek, string>>; total?: string } {
  const perDay: Partial<Record<DayOfWeek, string>> = {}
  for (const day of DAYS) {
    const slots = schedule[day.key]
    if (slots.some((s) => !HOUR_MINUTE.test(s.start) || !HOUR_MINUTE.test(s.end))) {
      perDay[day.key] = 'Preencha o início e o fim de todos os horários.'
      continue
    }
    if (slots.some((s) => s.start >= s.end)) {
      perDay[day.key] = 'Em cada horário, o início precisa ser antes do fim.'
      continue
    }
    const sorted = [...slots].sort((a, b) => a.start.localeCompare(b.start))
    if (sorted.some((s, i) => i > 0 && s.start < sorted[i - 1].end)) perDay[day.key] = 'Há horários que se sobrepõem neste dia.'
  }
  const total = countWindows(schedule) > MAX_WINDOWS ? `No máximo ${MAX_WINDOWS} horários no total.` : undefined
  return { perDay, total }
}
