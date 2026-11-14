import { ApiError } from './client'

/** O prestador ainda não cadastrou o negócio (404 com código estável): as telas mostram o convite para cadastrar. */
export function isNotConfigured(error: unknown): boolean {
  return error instanceof ApiError && error.reason === 'PROVIDER_NOT_CONFIGURED'
}
