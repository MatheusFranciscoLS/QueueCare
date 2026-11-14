import type { AgendaItem } from './agenda'

/** Contagem por situação e o próximo atendimento ainda por vir (agendado ou já chegou), em ordem de horário. */
export function summarize(items: AgendaItem[], now: Date) {
  const count = (status: string) => items.filter((i) => i.status === status).length
  const next = items
    .filter((i) => (i.status === 'SCHEDULED' || i.status === 'CHECKED_IN') && new Date(i.endsAt) > now)
    .sort((a, b) => a.startsAt.localeCompare(b.startsAt))[0]
  return { scheduled: count('SCHEDULED'), checkedIn: count('CHECKED_IN'), completed: count('COMPLETED'), next }
}
