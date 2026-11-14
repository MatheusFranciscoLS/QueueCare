import { ApiError } from './client'

/** Códigos estáveis da API (campo `reason`) em mensagens para a pessoa. Nunca repete dados digitados. */
const BY_REASON: Record<string, string> = {
  INVALID_CURRENT_PASSWORD: 'A senha atual não confere.',
  PASSWORD_CHANGE_REQUIRED: 'Troque a sua senha antes de continuar.',
  RESOURCE_BUSY: 'O sistema está ocupado com outra alteração. Tente de novo em instantes.',
  ACCOUNT_CONFLICT: 'Não foi possível criar a conta com os dados informados.',
  STAFF_ALREADY_ASSIGNED: 'Essa pessoa já está vinculada a um prestador.',
  STAFF_NOT_FOUND: 'Não encontramos esse atendente.',
  PROVIDER_NOT_CONFIGURED: 'Cadastre primeiro o seu negócio.',
  ALREADY_CANCELLED: 'Esse agendamento já foi cancelado.',
  NOT_CANCELLABLE: 'Esse agendamento não pode mais ser cancelado.',
  TOO_LATE: 'Passou do prazo para cancelar.',
  CONCURRENT_UPDATE: 'O agendamento acabou de ser alterado por outra pessoa. Atualize a agenda.',
  INVALID_STATE: 'Esse agendamento já mudou de situação. Atualize a agenda.',
  WRONG_DAY: 'A chegada só pode ser registrada no dia do agendamento.',
  APPOINTMENT_NOT_FOUND: 'Agendamento não encontrado.',
  SLOT_TAKEN: 'Esse horário acabou de ser ocupado por outra pessoa. Escolha outro.',
  IN_THE_PAST: 'Esse horário já passou. Escolha outro.',
  TOO_SOON: 'Esse horário está muito próximo: o prestador exige mais antecedência.',
  OUTSIDE_AVAILABILITY: 'Esse horário está fora do expediente do prestador.',
  NONEXISTENT_LOCAL_TIME: 'Esse horário não existe nesse dia (mudança de horário de verão). Escolha outro.',
  AMBIGUOUS_LOCAL_TIME: 'Esse horário é ambíguo nesse dia (mudança de horário de verão). Escolha outro.',
  SERVICE_NOT_FOUND_OR_INACTIVE: 'Esse serviço não está mais disponível.',
  PROVIDER_NOT_FOUND: 'Esse prestador não está mais disponível.',
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
      return 'Essa ação conflita com o estado atual. Atualize a página e tente de novo.'
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
