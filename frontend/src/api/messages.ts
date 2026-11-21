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
  TOO_LATE: 'Passou do prazo para cancelar ou remarcar.',
  CONCURRENT_UPDATE: 'O agendamento acabou de ser alterado por outra pessoa. Atualize a agenda.',
  INVALID_STATE: 'Esse agendamento já mudou de situação. Atualize a agenda.',
  WRONG_DAY: 'A chegada só pode ser registrada no dia do agendamento.',
  TOO_EARLY: 'Só dá para registrar a falta a partir do horário do agendamento.',
  SLUG_TAKEN: 'Esse endereço já está em uso por outro estúdio. Escolha outro.',
  STUDIO_NOT_FOUND: 'Não encontramos esse estúdio.',
  WAITLIST_LIMIT: 'Você já está em muitas listas de espera. Saia de uma para entrar em outra.',
  INVALID_DATE: 'Esse dia não está disponível para a lista de espera.',
  WAITLIST_ENTRY_NOT_FOUND: 'Essa entrada da lista de espera não existe mais.',
  CANNOT_DEACTIVATE_SELF: 'Você não pode desativar a própria conta.',
  LAST_ACTIVE_ADMIN: 'É preciso manter ao menos um administrador ativo.',
  USER_NOT_FOUND: 'Essa conta não foi encontrada.',
  APPOINTMENT_NOT_FOUND: 'Agendamento não encontrado.',
  SLOT_TAKEN: 'Esse horário acabou de ser ocupado por outra pessoa. Escolha outro.',
  IN_THE_PAST: 'Esse horário já passou. Escolha outro.',
  TOO_SOON: 'Esse horário está muito próximo: o prestador exige mais antecedência.',
  OUTSIDE_AVAILABILITY: 'Esse horário está fora do expediente do prestador.',
  CLOSED_DAY: 'O estúdio está fechado nesse dia. Escolha outro dia.',
  NONEXISTENT_LOCAL_TIME: 'Esse horário não existe nesse dia (mudança de horário de verão). Escolha outro.',
  AMBIGUOUS_LOCAL_TIME: 'Esse horário é ambíguo nesse dia (mudança de horário de verão). Escolha outro.',
  SERVICE_NOT_FOUND_OR_INACTIVE: 'Esse serviço não está mais disponível.',
  PROVIDER_NOT_FOUND: 'Esse prestador não está mais disponível.',
  RESCHEDULE_LIMIT: 'Você já usou as 3 remarcações deste agendamento. Cancele e agende de novo, ou fale com o estúdio.',
  NO_CHANGE: 'Esse já é o horário do agendamento. Escolha outro.',
  NOT_RESCHEDULABLE: 'Esse agendamento já começou e não pode mais ser remarcado.',
  PET_NOT_FOUND: 'Não encontramos esse pet. Atualize a lista e escolha de novo.',
  PET_INACTIVE: 'Esse pet está arquivado. Reative-o em Meus pets ou escolha outro.',
  PET_LIMIT_REACHED: 'Você chegou ao limite de pets. Arquive um pet que não usa mais para cadastrar ou reativar outro.',
  INVALID_PET: 'Confira os dados do pet.',
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
