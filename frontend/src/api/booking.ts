import type { AuthApi } from '../auth/session'

export interface CatalogProvider {
  id: string
  businessName: string
  timeZone: string
  /** Prazo de cancelamento e WhatsApp do estúdio (ADR 0021): a regra aparece antes de reservar. */
  cancellationDeadlineMinutes?: number
  phone?: string | null
}

export interface CatalogService {
  id: string
  name: string
  durationMinutes: number
  /** Duração por porte e extra de pelo (ADR 0020); nulos = usam a base. */
  durationMediumMinutes?: number | null
  durationLargeMinutes?: number | null
  coatExtraMinutes?: number | null
  /** Preços em centavos (ADR 0019); todos nulos = a combinar. Médio e grande nulos valem o base. */
  priceCents?: number | null
  priceMediumCents?: number | null
  priceLargeCents?: number | null
}

/** Horário livre: `localStart` é o horário LOCAL do negócio (AAAA-MM-DDTHH:mm:ss), o mesmo que se envia para reservar. */
export interface FreeSlot {
  localStart: string
  startsAt: string
  endsAt: string
}

export interface BookedAppointment {
  id: string
  providerId: string
  serviceId: string
  startsAt: string
  endsAt: string
  status: string
  businessName: string | null
  timeZone: string | null
  serviceName: string | null
  /** Valor congelado na reserva, em centavos; nulo = a combinar. */
  priceCents?: number | null
  /** Cópia dos dados do pet no momento da reserva (só nome e espécie); nulo em agendamento sem pet. */
  pet?: { name: string; species: string } | null
  /** Até quando cancelar ou remarcar de graça (só enquanto agendado) e o WhatsApp do estúdio (ADR 0021). */
  freeChangeUntil?: string | null
  studioPhone?: string | null
}

/** Uma linha do histórico. `by`: quem agiu, sem nomes de funcionários para o cliente. Horários só nas remarcações. */
export interface HistoryEntry {
  type: 'SCHEDULED' | 'RESCHEDULED' | 'CHECKED_IN' | 'STARTED' | 'READY' | 'COMPLETED' | 'CANCELLED' | 'NO_SHOW'
  at: string
  by: 'CUSTOMER' | 'STAFF' | 'SYSTEM'
  actorName?: string | null
  previousStartsAt?: string | null
  newStartsAt?: string | null
}

type Request = AuthApi['request']

/** Escolha e reserva pelo cliente. As regras (disponibilidade, antecedência, reserva dupla) são da API; aqui só se monta a requisição. */
export function bookingApi(request: Request) {
  return {
    providers: (page = 0, size = 50) => request<CatalogProvider[]>(`/api/catalog/providers?page=${page}&size=${size}`),
    services: (providerId: string) => request<CatalogService[]>(`/api/catalog/providers/${encodeURIComponent(providerId)}/services`),
    slots: (providerId: string, serviceId: string, date: string, excludeAppointmentId?: string, petId?: string) =>
      request<FreeSlot[]>(
        `/api/providers/${encodeURIComponent(providerId)}/slots?serviceId=${encodeURIComponent(serviceId)}&date=${encodeURIComponent(date)}` +
          (excludeAppointmentId ? `&excludeAppointmentId=${encodeURIComponent(excludeAppointmentId)}` : '') +
          // A duração depende do pet (ADR 0020): a API só considera um pet ativo do próprio cliente.
          (petId ? `&petId=${encodeURIComponent(petId)}` : ''),
      ),
    /** Remarcar: move o MESMO agendamento para o novo horário LOCAL do negócio. */
    reschedule: (appointmentId: string, localStart: string) =>
      request<BookedAppointment>(`/api/appointments/${encodeURIComponent(appointmentId)}/reschedule`, { method: 'POST', body: { localStart } }),
    /** Histórico do agendamento (o cliente vê "cliente" ou "equipe", nunca o nome de um funcionário). */
    history: (appointmentId: string) => request<HistoryEntry[]>(`/api/appointments/${encodeURIComponent(appointmentId)}/events`),
    mine: (page = 0, size = 50) => request<BookedAppointment[]>(`/api/appointments/mine?page=${page}&size=${size}`),
    cancel: (appointmentId: string) => request<BookedAppointment>(`/api/appointments/${encodeURIComponent(appointmentId)}/cancel`, { method: 'POST' }),
    book: (input: { providerId: string; serviceId: string; localStart: string; petId: string }) => request<BookedAppointment>('/api/appointments', { method: 'POST', body: input }),
  }
}
