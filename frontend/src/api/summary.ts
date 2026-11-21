import type { AgendaItem } from './agenda'

/** Contagem por situação e o próximo atendimento ainda por vir (agendado ou já chegou), em ordem de horário. */
export function summarize(items: AgendaItem[], now: Date) {
  const count = (status: string) => items.filter((i) => i.status === status).length
  const next = items
    .filter((i) => (i.status === 'SCHEDULED' || i.status === 'CHECKED_IN' || i.status === 'IN_PROGRESS' || i.status === 'READY') && new Date(i.endsAt) > now)
    .sort((a, b) => a.startsAt.localeCompare(b.startsAt))[0]
  return { scheduled: count('SCHEDULED'), checkedIn: count('CHECKED_IN') + count('IN_PROGRESS') + count('READY'), completed: count('COMPLETED'), next }
}

/**
 * Lembretes de amanhã (ADR 0021): quantos agendamentos de amanhã ainda estão agendados e quantos deles a equipe PODE lembrar (tutor com telefone) e ainda
 * não lembrou. Quem não tem telefone não entra na conta: o app não consegue lembrar essa pessoa.
 */
export function tomorrowReminders(items: AgendaItem[]) {
  const scheduled = items.filter((i) => i.status === 'SCHEDULED')
  const pending = scheduled.filter((i) => !!i.customerPhone && !i.remindedAt)
  return { scheduled: scheduled.length, pending: pending.length }
}
