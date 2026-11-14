import type { AuthApi } from '../auth/session'

export interface Workplace {
  businessName: string
  timeZone: string
}

export interface AgendaItem {
  id: string
  startsAt: string
  endsAt: string
  status: string
  serviceId: string
  serviceName: string | null
  customerId: string
  customerName: string | null
}

type Request = AuthApi['request']

/** Agenda do dia e cancelamento pela equipe. O prestador vem do usuário autenticado, nunca da requisição. */
export function agendaApi(request: Request) {
  return {
    workplace: () => request<Workplace>('/api/agenda/workplace'),
    day: (date: string) => request<AgendaItem[]>(`/api/agenda?date=${encodeURIComponent(date)}`),
    cancel: (appointmentId: string) => request<unknown>(`/api/appointments/${encodeURIComponent(appointmentId)}/cancel`, { method: 'POST' }),
  }
}
