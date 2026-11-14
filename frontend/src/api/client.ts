/**
 * Cliente HTTP da API. Todas as chamadas passam por aqui: o token Bearer é anexado em um só lugar e os erros da API
 * (application/problem+json) viram {@link ApiError} com status, código estável (`reason`) e erros por campo.
 */
import { apiUrl } from './base'
import { trackRequest } from './slow'

export class ApiError extends Error {
  readonly status: number
  readonly reason?: string
  readonly fieldErrors: Record<string, string>
  readonly retryAfterSeconds?: number

  constructor(
    status: number,
    message: string,
    reason?: string,
    fieldErrors: Record<string, string> = {},
    retryAfterSeconds?: number,
  ) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.reason = reason
    this.fieldErrors = fieldErrors
    this.retryAfterSeconds = retryAfterSeconds
  }
}

export interface RequestOptions {
  method?: 'GET' | 'POST' | 'PUT' | 'DELETE'
  body?: unknown
  token?: string | null
  signal?: AbortSignal
}

export async function apiRequest<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const headers: Record<string, string> = { Accept: 'application/json' }
  if (options.body !== undefined) headers['Content-Type'] = 'application/json'
  if (options.token) headers.Authorization = `Bearer ${options.token}`

  let response: Response
  const done = trackRequest()
  try {
    response = await fetch(apiUrl(path), {
      method: options.method ?? 'GET',
      headers,
      body: options.body === undefined ? undefined : JSON.stringify(options.body),
      signal: options.signal,
      credentials: 'omit', // a API não usa cookies (ADR 0003)
    })
  } catch (e) {
    if (e instanceof DOMException && e.name === 'AbortError') throw e
    throw new ApiError(0, 'sem conexão com o servidor', 'NETWORK')
  } finally {
    done()
  }

  if (response.ok) {
    if (response.status === 204) return undefined as T
    return (await response.json()) as T
  }
  throw await toApiError(response)
}

async function toApiError(response: Response): Promise<ApiError> {
  let detail = ''
  let reason: string | undefined
  let fieldErrors: Record<string, string> = {}
  try {
    const problem = (await response.json()) as {
      detail?: unknown
      reason?: unknown
      errors?: unknown
    }
    if (typeof problem.detail === 'string') detail = problem.detail
    if (typeof problem.reason === 'string') reason = problem.reason
    if (problem.errors && typeof problem.errors === 'object') {
      fieldErrors = Object.fromEntries(
        Object.entries(problem.errors as Record<string, unknown>).filter(([, v]) => typeof v === 'string'),
      ) as Record<string, string>
    }
  } catch {
    // corpo vazio ou que não é JSON: segue só com o status
  }
  const retryAfter = Number(response.headers.get('Retry-After'))
  return new ApiError(response.status, detail, reason, fieldErrors, Number.isFinite(retryAfter) && retryAfter > 0 ? retryAfter : undefined)
}
