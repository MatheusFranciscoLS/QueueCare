import { ApiError } from './client'

/** Códigos estáveis da API (campo `reason`) em mensagens para a pessoa. Nunca repete dados digitados. */
const BY_REASON: Record<string, string> = {
  INVALID_CURRENT_PASSWORD: 'A senha atual não confere.',
  PASSWORD_CHANGE_REQUIRED: 'Troque a sua senha antes de continuar.',
  RESOURCE_BUSY: 'O sistema está ocupado com outra alteração. Tente de novo em instantes.',
  ACCOUNT_CONFLICT: 'Não foi possível criar a conta com os dados informados.',
}

export function describeError(error: unknown): string {
  if (!(error instanceof ApiError)) return 'Algo deu errado. Tente novamente.'
  if (error.reason && BY_REASON[error.reason]) return BY_REASON[error.reason]
  switch (error.status) {
    case 0:
      return 'Sem conexão com o servidor. Verifique a internet e tente de novo.'
    case 400: {
      const first = Object.values(error.fieldErrors)[0]
      return first ? first : 'Confira os dados informados.'
    }
    case 401:
      return 'E-mail ou senha incorretos.'
    case 403:
      return 'Você não tem permissão para isso.'
    case 404:
      return 'Não encontramos o que você procura.'
    case 409:
      return error.message || 'Essa ação conflita com o estado atual. Atualize e tente de novo.'
    case 413:
      return 'Os dados enviados são grandes demais.'
    case 429:
      return error.retryAfterSeconds
        ? `Muitas tentativas. Tente de novo em ${formatWait(error.retryAfterSeconds)}.`
        : 'Muitas tentativas. Tente de novo mais tarde.'
    default:
      return 'Algo deu errado no servidor. Tente novamente em instantes.'
  }
}

function formatWait(seconds: number): string {
  if (seconds < 90) return `${seconds} segundos`
  return `${Math.ceil(seconds / 60)} minutos`
}
