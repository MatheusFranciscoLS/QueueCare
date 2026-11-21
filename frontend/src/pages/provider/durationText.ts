import { MAX_COAT_EXTRA_MINUTES, MAX_SERVICE_MINUTES, MIN_SERVICE_MINUTES } from '../../api/duration'
import type { ServiceDurations } from '../../api/provider'

/** O que a pessoa digitou nos três campos opcionais de duração (ADR 0020). Vazio nos dois de porte = usa a base; vazio no extra = nenhum. */
export interface DurationText {
  medium: string
  large: string
  coatExtra: string
}

export type DurationErrors = { durationMedium?: string; durationLarge?: string; coatExtra?: string }

export const EMPTY_DURATION_TEXT: DurationText = { medium: '', large: '', coatExtra: '' }

/** Os campos de duração de um serviço já salvo, como texto. */
export function durationTextOf(s: { durationMediumMinutes?: number | null; durationLargeMinutes?: number | null; coatExtraMinutes?: number | null }): DurationText {
  return {
    medium: s.durationMediumMinutes == null ? '' : String(s.durationMediumMinutes),
    large: s.durationLargeMinutes == null ? '' : String(s.durationLargeMinutes),
    coatExtra: s.coatExtraMinutes ? String(s.coatExtraMinutes) : '',
  }
}

function readMinutes(text: string, min: number, max: number): number | null | undefined {
  if (!text.trim()) return null
  const n = Number(text)
  return Number.isInteger(n) && n >= min && n <= max ? n : undefined
}

/**
 * Lê os três campos. A API repete tudo isso e é quem decide; aqui só se evita a ida e volta. O pior caso (a maior duração somada ao extra)
 * também não pode passar do teto, porque seria um atendimento que a API recusaria ao reservar.
 */
export function readDurations(text: DurationText, base: number): { durations?: ServiceDurations; errors: DurationErrors } {
  const medium = readMinutes(text.medium, MIN_SERVICE_MINUTES, MAX_SERVICE_MINUTES)
  const large = readMinutes(text.large, MIN_SERVICE_MINUTES, MAX_SERVICE_MINUTES)
  const extra = readMinutes(text.coatExtra, 0, MAX_COAT_EXTRA_MINUTES)
  const range = `Informe de ${MIN_SERVICE_MINUTES} a ${MAX_SERVICE_MINUTES} minutos, ou deixe vazio.`
  const errors: DurationErrors = {
    durationMedium: medium === undefined ? range : undefined,
    durationLarge: large === undefined ? range : undefined,
    coatExtra: extra === undefined ? `Informe de 0 a ${MAX_COAT_EXTRA_MINUTES} minutos, ou deixe vazio.` : undefined,
  }
  if (!errors.durationMedium && !errors.durationLarge && !errors.coatExtra) {
    const longest = Math.max(base, medium ?? 0, large ?? 0)
    if (Number.isFinite(base) && longest + (extra ?? 0) > MAX_SERVICE_MINUTES) {
      errors.coatExtra = `A maior duração somada a este extra não pode passar de ${MAX_SERVICE_MINUTES} minutos.`
    }
  }
  if (errors.durationMedium || errors.durationLarge || errors.coatExtra) return { errors }
  return { durations: { durationMediumMinutes: medium ?? null, durationLargeMinutes: large ?? null, coatExtraMinutes: extra ?? 0 }, errors }
}
