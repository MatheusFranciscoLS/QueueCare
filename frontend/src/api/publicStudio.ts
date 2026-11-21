import { apiRequest } from './client'

/** Página pública do estúdio (ADR 0015): aberta a qualquer visitante, sem login. Só o que o estúdio publicou de propósito. */
export interface PublicService {
  name: string
  /** A MENOR duração possível (o "a partir de"). */
  durationMinutes: number
  /** A duração muda com o porte ou o pelo do pet (ADR 0020). */
  durationVaries: boolean
}

export interface PublicStudio {
  slug: string
  businessName: string
  timeZone: string
  /** Até quanto tempo antes o cliente cancela ou remarca de graça (ADR 0021). */
  cancellationDeadlineMinutes: number
  services: PublicService[]
}

export function fetchPublicStudio(slug: string): Promise<PublicStudio> {
  return apiRequest<PublicStudio>(`/api/public/studios/${encodeURIComponent(slug)}`)
}

/** O endereço do dono (ADR 0015): `slug` nulo = sem endereço; só aparece ao público quando `published`. */
export interface PublicPageSettings {
  slug: string | null
  published: boolean
}

/** Caminho do link público de um endereço: `/p/estudio-bem-te-vi`. */
export function publicPath(slug: string): string {
  return `/p/${slug}`
}
