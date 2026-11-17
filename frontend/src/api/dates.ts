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

/** Segunda-feira da semana (AAAA-MM-DD) da data dada: a semana da agenda começa na segunda. */
export function startOfWeek(date: string): string {
  const [y, m, day] = date.split('-').map(Number)
  const weekday = new Date(Date.UTC(y, m - 1, day)).getUTCDay() // 0 = domingo
  return addDays(date, -((weekday + 6) % 7))
}

/** Dia da semana abreviado ("seg", "ter"...), com a inicial maiúscula: "Seg". */
export function shortWeekday(date: string): string {
  const [y, m, d] = date.split('-').map(Number)
  const text = new Intl.DateTimeFormat('pt-BR', { timeZone: 'UTC', weekday: 'short' }).format(new Date(Date.UTC(y, m - 1, d))).replace('.', '')
  return text.charAt(0).toUpperCase() + text.slice(1)
}

/** "16 a 22 de novembro de 2026" (mesmo mês) ou "30 de novembro a 6 de dezembro de 2026" (meses ou anos diferentes). */
export function weekTitle(start: string): string {
  const end = addDays(start, 6)
  const part = (date: string, options: Intl.DateTimeFormatOptions) => {
    const [y, m, d] = date.split('-').map(Number)
    return new Intl.DateTimeFormat('pt-BR', { timeZone: 'UTC', ...options }).format(new Date(Date.UTC(y, m - 1, d)))
  }
  if (start.slice(0, 7) === end.slice(0, 7)) return `${Number(start.slice(8))} a ${part(end, { day: 'numeric', month: 'long', year: 'numeric' })}`
  if (start.slice(0, 4) === end.slice(0, 4)) return `${part(start, { day: 'numeric', month: 'long' })} a ${part(end, { day: 'numeric', month: 'long', year: 'numeric' })}`
  return `${part(start, { day: 'numeric', month: 'long', year: 'numeric' })} a ${part(end, { day: 'numeric', month: 'long', year: 'numeric' })}`
}
