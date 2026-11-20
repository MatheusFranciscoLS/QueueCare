import type { AuthApi } from '../auth/session'
import type { PetOnAppointment } from './pets'

export interface Workplace {
  /** Prestador em que a pessoa opera (para consultar horários livres ao remarcar). */
  providerId: string
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
  /** Valor congelado na reserva (centavos); vem na agenda do dia e na fila, nunca na semana. */
  priceCents?: number | null
  customerId: string
  customerName: string | null
  /** WhatsApp do tutor (só dígitos com DDI); vem na agenda do dia e na fila, nunca na semana; ausente se o tutor não informou. */
  customerPhone?: string | null
  /** O pet (cópia gravada no agendamento); nulo em agendamento sem pet. Raça e observações vêm na agenda do dia e na fila. */
  pet?: PetOnAppointment | null
}

/** Quem chegou e espera, por ordem de chegada (a posição vem do servidor). */
export interface QueueItem {
  position: number
  id: string
  startsAt: string
  endsAt: string
  serviceId: string
  serviceName: string | null
  priceCents?: number | null
  customerId: string
  customerName: string | null
  customerPhone?: string | null
  checkedInAt: string
  pet?: PetOnAppointment | null
}

type Request = AuthApi['request']

/** Agenda do dia, cancelamento, chegada, conclusão e fila, pela equipe. O prestador vem do usuário autenticado, nunca da requisição. */
export function agendaApi(request: Request) {
  return {
    workplace: () => request<Workplace>('/api/agenda/workplace'),
    day: (date: string) => request<AgendaItem[]>(`/api/agenda?date=${encodeURIComponent(date)}`),
    /** Os 7 dias a partir de `start` (data local do negócio), mesma resposta da agenda do dia. */
    week: (start: string) => request<AgendaItem[]>(`/api/agenda/week?start=${encodeURIComponent(start)}`),
    queue: () => request<QueueItem[]>('/api/agenda/queue'),
    checkIn: (appointmentId: string) => request<unknown>(`/api/appointments/${encodeURIComponent(appointmentId)}/check-in`, { method: 'POST' }),
    complete: (appointmentId: string) => request<unknown>(`/api/appointments/${encodeURIComponent(appointmentId)}/complete`, { method: 'POST' }),
    /** Remarcar: move o MESMO agendamento para o novo horário LOCAL do negócio (equipe: sem prazo e sem limite). */
    reschedule: (appointmentId: string, localStart: string) =>
      request<{ startsAt: string; endsAt: string }>(`/api/appointments/${encodeURIComponent(appointmentId)}/reschedule`, { method: 'POST', body: { localStart } }),
    cancel: (appointmentId: string) => request<unknown>(`/api/appointments/${encodeURIComponent(appointmentId)}/cancel`, { method: 'POST' }),
  }
}
