/** Duração do atendimento por porte e pelo (ADR 0020). Mesma regra da API (ServiceOffering.durationFor). */
export const MIN_SERVICE_MINUTES = 5
export const MAX_SERVICE_MINUTES = 480
export const MAX_COAT_EXTRA_MINUTES = 240

export interface Durations {
  durationMinutes: number
  /** Nulos = usam a duração base. */
  durationMediumMinutes?: number | null
  durationLargeMinutes?: number | null
  /** Minutos somados para pelo longo, cacheado ou duplo (0 = nenhum). */
  coatExtraMinutes?: number | null
}

const LONG_COATS = new Set(['LONG', 'CURLY', 'DOUBLE'])

/** A duração para o pet: a do porte (pequeno e sem porte usam a base) mais o extra de pelo longo, cacheado ou duplo. */
export function durationForPet(service: Durations, size: string | null | undefined, coat: string | null | undefined): number {
  let minutes = service.durationMinutes
  if (size === 'MEDIUM' && service.durationMediumMinutes != null) minutes = service.durationMediumMinutes
  else if (size === 'LARGE' && service.durationLargeMinutes != null) minutes = service.durationLargeMinutes
  if (coat && LONG_COATS.has(coat)) minutes += service.coatExtraMinutes ?? 0
  return minutes
}

/** Texto da duração de um serviço sem saber o pet: "60 min" ou "a partir de 60 min" quando varia com o porte ou o pelo. */
export function durationSummary(service: Durations): string {
  const sizes = [service.durationMinutes, service.durationMediumMinutes, service.durationLargeMinutes].filter((m): m is number => m != null)
  const varies = sizes.some((m) => m !== service.durationMinutes) || (service.coatExtraMinutes ?? 0) > 0
  return varies ? `a partir de ${Math.min(...sizes)} min` : `${service.durationMinutes} min`
}

/** Linha do estúdio: "60 min", ou "P 60 · M 90 · G 120 min" com duração por porte, e "+30 min para pelo longo, cacheado ou duplo". */
export function durationLine(service: Durations): string {
  const hasSizes = service.durationMediumMinutes != null || service.durationLargeMinutes != null
  const base = hasSizes
    ? `P ${service.durationMinutes} · M ${service.durationMediumMinutes ?? service.durationMinutes} · G ${service.durationLargeMinutes ?? service.durationMinutes} min`
    : `${service.durationMinutes} min`
  const extra = service.coatExtraMinutes ?? 0
  return extra > 0 ? `${base} · +${extra} min para pelo longo, cacheado ou duplo` : base
}
