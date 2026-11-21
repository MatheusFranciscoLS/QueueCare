import type { AuthApi } from '../auth/session'
import { addDays } from './dates'

/** Contagens do período por situação. `inProgress`: chegou, em atendimento ou pronto (ainda não concluído). */
export interface ReportCounts {
  completed: number
  noShow: number
  cancelled: number
  scheduled: number
  inProgress: number
}

/** Em centavos. NENHUM destes valores é "faturamento" (ADR 0031): são o valor de tabela congelado na reserva. */
export interface ReportAmounts {
  completedCents: number
  expectedCents: number
  depositsReceivedCents: number
}

export interface ReportSummary {
  from: string
  to: string
  counts: ReportCounts
  amounts: ReportAmounts
  byService: { service: string; completed: number; completedCents: number }[]
  byDay: { date: string; completed: number; noShow: number; cancelled: number }[]
  topNoShows: { customerId: string; name: string; noShows: number }[]
}

export interface LapsedCustomer {
  customerId: string
  name: string
  phone?: string | null
  lastVisitAt: string
  daysSince: number
}

type Request = AuthApi['request']

/** O resumo do estúdio (ADR 0031): só o dono. */
export function reportsApi(request: Request) {
  return {
    summary: (from: string, to: string) => request<ReportSummary>(`/api/provider/reports/summary?from=${encodeURIComponent(from)}&to=${encodeURIComponent(to)}`),
    lapsed: (days: number) => request<LapsedCustomer[]>(`/api/provider/reports/lapsed?days=${days}`),
  }
}

export type PeriodKey = 'today' | '7d' | '30d' | 'month'

export const PERIODS: { key: PeriodKey; label: string }[] = [
  { key: 'today', label: 'Hoje' },
  { key: '7d', label: '7 dias' },
  { key: '30d', label: '30 dias' },
  { key: 'month', label: 'Este mês' },
]

/** As datas do período, em dias do ESTÚDIO (`today` já vem do fuso dele). "7 dias" inclui hoje: de 6 dias atrás até hoje. */
export function periodRange(key: PeriodKey, today: string): { from: string; to: string } {
  switch (key) {
    case 'today':
      return { from: today, to: today }
    case '7d':
      return { from: addDays(today, -6), to: today }
    case '30d':
      return { from: addDays(today, -29), to: today }
    case 'month':
      return { from: `${today.slice(0, 8)}01`, to: today }
  }
}

/** Mensagem para chamar de volta quem sumiu: o app só prepara o texto; quem envia é a equipe, tocando no link. */
export function comeBackMessage(name: string, studioName: string): string {
  const first = name.trim().split(/\s+/)[0] || name
  return `Olá, ${first}! Aqui é do ${studioName}. Faz um tempinho que não vemos você e o seu pet por aqui. Quer marcar um horário?`
}
