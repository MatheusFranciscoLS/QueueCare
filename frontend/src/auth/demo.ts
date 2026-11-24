import type { User } from './session'

/** As contas da demonstração são as do domínio reservado `.invalid`, que o cadastro público nunca cria (ADR 0041). */
export const isDemoUser = (user: User | null) => user?.email.endsWith('@demo.invalid') === true
