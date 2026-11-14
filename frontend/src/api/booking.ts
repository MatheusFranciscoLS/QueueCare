import type { AuthApi } from '../auth/session'

export interface CatalogProvider {
  id: string
  businessName: string
  timeZone: string
}

export interface CatalogService {
  id: string
  name: string
  durationMinutes: number
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
}

type Request = AuthApi['request']

/** Escolha e reserva pelo cliente. As regras (disponibilidade, antecedência, reserva dupla) são da API; aqui só se monta a requisição. */
export function bookingApi(request: Request) {
  return {
    providers: (page = 0, size = 50) => request<CatalogProvider[]>(`/api/catalog/providers?page=${page}&size=${size}`),
    services: (providerId: string) => request<CatalogService[]>(`/api/catalog/providers/${encodeURIComponent(providerId)}/services`),
    slots: (providerId: string, serviceId: string, date: string) =>
      request<FreeSlot[]>(`/api/providers/${encodeURIComponent(providerId)}/slots?serviceId=${encodeURIComponent(serviceId)}&date=${encodeURIComponent(date)}`),
    mine: (page = 0, size = 50) => request<BookedAppointment[]>(`/api/appointments/mine?page=${page}&size=${size}`),
    cancel: (appointmentId: string) => request<BookedAppointment>(`/api/appointments/${encodeURIComponent(appointmentId)}/cancel`, { method: 'POST' }),
    book: (input: { providerId: string; serviceId: string; localStart: string }) => request<BookedAppointment>('/api/appointments', { method: 'POST', body: input }),
  }
}
