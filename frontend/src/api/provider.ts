import type { AuthApi } from '../auth/session'

export interface Business {
  id: string
  businessName: string
  timeZone: string
  minimumNoticeMinutes: number
  cancellationDeadlineMinutes: number
}

export type BusinessInput = Omit<Business, 'id'>

export interface Service {
  id: string
  name: string
  durationMinutes: number
  active: boolean
  /** Preços em centavos (ADR 0019); nulos = a combinar. Médio e grande nulos usam o base. */
  priceCents?: number | null
  priceMediumCents?: number | null
  priceLargeCents?: number | null
}

export interface ServicePrices {
  priceCents: number | null
  priceMediumCents: number | null
  priceLargeCents: number | null
}

export type DayOfWeek = 'MONDAY' | 'TUESDAY' | 'WEDNESDAY' | 'THURSDAY' | 'FRIDAY' | 'SATURDAY' | 'SUNDAY'

/** Janela semanal em horário LOCAL do prestador; a API devolve "08:00:00" e aceita "08:00". */
export interface AvailabilityWindow {
  dayOfWeek: DayOfWeek
  start: string
  end: string
}

type Request = AuthApi['request']

/** Chamadas do prestador (negócio, serviços e expediente). A API decide tudo: aqui só se monta a requisição. */
export function providerApi(request: Request) {
  return {
    getBusiness: () => request<Business>('/api/provider'),
    createBusiness: (input: BusinessInput) => request<Business>('/api/provider', { method: 'POST', body: input }),
    updateBusiness: (input: BusinessInput) => request<Business>('/api/provider', { method: 'PUT', body: input }),
    listServices: () => request<Service[]>('/api/provider/services'),
    createService: (input: { name: string; durationMinutes: number } & ServicePrices) => request<Service>('/api/provider/services', { method: 'POST', body: input }),
    updateService: (id: string, input: { name: string; durationMinutes: number; active: boolean } & ServicePrices) =>
      request<Service>(`/api/provider/services/${encodeURIComponent(id)}`, { method: 'PUT', body: input }),
    getAvailability: () => request<AvailabilityWindow[]>('/api/provider/availability'),
    replaceAvailability: (windows: AvailabilityWindow[]) => request<AvailabilityWindow[]>('/api/provider/availability', { method: 'PUT', body: windows }),
  }
}
