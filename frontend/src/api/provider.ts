import type { AuthApi } from '../auth/session'

export interface Business {
  id: string
  businessName: string
  timeZone: string
  minimumNoticeMinutes: number
  cancellationDeadlineMinutes: number
  /** WhatsApp do estúdio (só dígitos com DDI); nulo = não informado (ADR 0021). */
  phone?: string | null
}

export type BusinessInput = Omit<Business, 'id' | 'phone'> & { phone?: string }

export interface Service {
  id: string
  name: string
  durationMinutes: number
  active: boolean
  /** Preços em centavos (ADR 0019); nulos = a combinar. Médio e grande nulos usam o base. */
  priceCents?: number | null
  priceMediumCents?: number | null
  priceLargeCents?: number | null
  /** Duração por porte e extra de pelo (ADR 0020); nulos = usam a duração base. */
  durationMediumMinutes?: number | null
  durationLargeMinutes?: number | null
  coatExtraMinutes?: number | null
}

export interface ServiceDurations {
  durationMediumMinutes: number | null
  durationLargeMinutes: number | null
  coatExtraMinutes: number
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
    createService: (input: { name: string; durationMinutes: number } & ServiceDurations & ServicePrices) => request<Service>('/api/provider/services', { method: 'POST', body: input }),
    updateService: (id: string, input: { name: string; durationMinutes: number; active: boolean } & ServiceDurations & ServicePrices) =>
      request<Service>(`/api/provider/services/${encodeURIComponent(id)}`, { method: 'PUT', body: input }),
    getAvailability: () => request<AvailabilityWindow[]>('/api/provider/availability'),
    replaceAvailability: (windows: AvailabilityWindow[]) => request<AvailabilityWindow[]>('/api/provider/availability', { method: 'PUT', body: windows }),
  }
}
