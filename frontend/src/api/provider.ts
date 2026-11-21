import type { AuthApi } from '../auth/session'

export interface Business {
  id: string
  businessName: string
  timeZone: string
  minimumNoticeMinutes: number
  cancellationDeadlineMinutes: number
  /** WhatsApp do estúdio (só dígitos com DDI); nulo = não informado (ADR 0021). */
  phone?: string | null
  /** Sinal por Pix (ADR 0023): chave, valor em centavos e quando pedir (padrão NEVER). */
  pixKey?: string | null
  depositCents?: number | null
  depositRule?: DepositRule
}

export type DepositRule = 'NEVER' | 'NEW_CUSTOMERS' | 'NEW_OR_REPEAT_NO_SHOW'

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

/** Folga, feriado ou férias (ADR 0024): dias inteiros, de `startsOn` a `endsOn` (inclusive), na data do estúdio. */
export interface TimeOff {
  id: string
  startsOn: string
  endsOn: string
  reason?: string | null
}

/** Ao fechar um período, quantos agendamentos ativos já existiam nele (nada é cancelado sozinho). */
export type TimeOffCreated = TimeOff & { affectedAppointments: number }

type Request = AuthApi['request']

/** Chamadas do prestador (negócio, serviços e expediente). A API decide tudo: aqui só se monta a requisição. */
export function providerApi(request: Request) {
  return {
    getBusiness: () => request<Business>('/api/provider'),
    /** Endereço e publicação da página pública (ADR 0015); slug nulo = sem endereço. */
    getPublicPage: () => request<{ slug: string | null; published: boolean }>('/api/provider/public-page'),
    updatePublicPage: (input: { slug: string | null; published: boolean }) => request<{ slug: string | null; published: boolean }>('/api/provider/public-page', { method: 'PUT', body: input }),
    /** Sinal por Pix (ADR 0023). Chave vazia limpa; regra ligada exige chave e valor. */
    updateDeposit: (input: { pixKey: string; depositCents: number | null; depositRule: DepositRule }) => request<Business>('/api/provider/deposit', { method: 'PUT', body: input }),
    createBusiness: (input: BusinessInput) => request<Business>('/api/provider', { method: 'POST', body: input }),
    updateBusiness: (input: BusinessInput) => request<Business>('/api/provider', { method: 'PUT', body: input }),
    listServices: () => request<Service[]>('/api/provider/services'),
    createService: (input: { name: string; durationMinutes: number } & ServiceDurations & ServicePrices) => request<Service>('/api/provider/services', { method: 'POST', body: input }),
    updateService: (id: string, input: { name: string; durationMinutes: number; active: boolean } & ServiceDurations & ServicePrices) =>
      request<Service>(`/api/provider/services/${encodeURIComponent(id)}`, { method: 'PUT', body: input }),
    /** Folgas, feriados e férias de hoje em diante (ADR 0024). */
    listTimeOff: () => request<TimeOff[]>('/api/provider/time-off'),
    createTimeOff: (input: { startsOn: string; endsOn: string; reason?: string }) => request<TimeOffCreated>('/api/provider/time-off', { method: 'POST', body: input }),
    deleteTimeOff: (id: string) => request<void>(`/api/provider/time-off/${encodeURIComponent(id)}`, { method: 'DELETE' }),
    getAvailability: () => request<AvailabilityWindow[]>('/api/provider/availability'),
    replaceAvailability: (windows: AvailabilityWindow[]) => request<AvailabilityWindow[]>('/api/provider/availability', { method: 'PUT', body: windows }),
  }
}
