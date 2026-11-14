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

/** Quem chegou e espera, por ordem de chegada (a posição vem do servidor). */
export interface QueueItem {
  position: number
  id: string
  startsAt: string
  endsAt: string
  serviceId: string
  serviceName: string | null
  customerId: string
  customerName: string | null
  checkedInAt: string
}

type Request = AuthApi['request']

/** Agenda do dia, cancelamento, chegada, conclusão e fila, pela equipe. O prestador vem do usuário autenticado, nunca da requisição. */
export function agendaApi(request: Request) {
  return {
    workplace: () => request<Workplace>('/api/agenda/workplace'),
    day: (date: string) => request<AgendaItem[]>(`/api/agenda?date=${encodeURIComponent(date)}`),
    queue: () => request<QueueItem[]>('/api/agenda/queue'),
    checkIn: (appointmentId: string) => request<unknown>(`/api/appointments/${encodeURIComponent(appointmentId)}/check-in`, { method: 'POST' }),
    complete: (appointmentId: string) => request<unknown>(`/api/appointments/${encodeURIComponent(appointmentId)}/complete`, { method: 'POST' }),
    cancel: (appointmentId: string) => request<unknown>(`/api/appointments/${encodeURIComponent(appointmentId)}/cancel`, { method: 'POST' }),
  }
}
