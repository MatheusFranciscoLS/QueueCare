/** Data de hoje (AAAA-MM-DD) no fuso do negócio, não no do aparelho: o prestador pode estar em outro fuso. */
export function todayIn(timeZone: string, now: Date = new Date()): string {
  return new Intl.DateTimeFormat('en-CA', { timeZone, year: 'numeric', month: '2-digit', day: '2-digit' }).format(now)
}

/** Soma dias a uma data AAAA-MM-DD (sem fuso: é só calendário). */
export function addDays(date: string, days: number): string {
  const [y, m, d] = date.split('-').map(Number)
  const result = new Date(Date.UTC(y, m - 1, d + days))
  return result.toISOString().slice(0, 10)
}

export function isDate(value: string): boolean {
  return /^\d{4}-\d{2}-\d{2}$/.test(value) && addDays(value, 0) === value
}

/** Hora (HH:mm) de um instante UTC no fuso do negócio. */
export function timeIn(instant: string, timeZone: string): string {
  return new Intl.DateTimeFormat('pt-BR', { timeZone, hour: '2-digit', minute: '2-digit', hour12: false }).format(new Date(instant))
}

/** Data por extenso no padrão brasileiro, para o título da agenda. */
export function longDate(date: string): string {
  const [y, m, d] = date.split('-').map(Number)
  return new Intl.DateTimeFormat('pt-BR', { timeZone: 'UTC', weekday: 'long', day: '2-digit', month: 'long', year: 'numeric' }).format(new Date(Date.UTC(y, m - 1, d)))
}
