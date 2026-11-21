import type { AuthApi } from '../auth/session'

/** Um tutor que o estúdio já atendeu (ADR 0025): só o que a agenda já mostra; e-mail nunca vem. */
export interface StudioCustomer {
  id: string
  name: string
  /** WhatsApp (só dígitos com DDI); nulo = o tutor não informou. */
  phone?: string | null
  /** Atendimentos concluídos e faltas neste estúdio. */
  visits: number
  noShows: number
  /** Início do agendamento mais recente (de qualquer estado). */
  lastAppointmentAt?: string | null
  /** Nomes dos pets que o estúdio atendeu (a cópia gravada nos agendamentos). */
  pets: string[]
}

type Request = AuthApi['request']

/** Clientes do estúdio: a busca é da equipe (dono e atendente); a senha temporária é só do dono. */
export function customersApi(request: Request) {
  return {
    search: (q: string) => request<StudioCustomer[]>(`/api/agenda/customers?q=${encodeURIComponent(q)}`),
    /** A senha aparece SÓ nesta resposta; o tutor terá de trocá-la ao entrar. */
    resetPassword: (customerId: string) => request<{ temporaryPassword: string }>(`/api/provider/customers/${encodeURIComponent(customerId)}/reset-password`, { method: 'POST' }),
  }
}

/** "3 atendimentos · 1 falta": só o que existe, no singular e no plural certos. */
export function customerSummary(c: Pick<StudioCustomer, 'visits' | 'noShows'>): string {
  const visits = c.visits === 1 ? '1 atendimento' : `${c.visits} atendimentos`
  if (c.noShows === 0) return visits
  return `${visits} · ${c.noShows === 1 ? '1 falta' : `${c.noShows} faltas`}`
}

/** A mensagem que o dono manda com a senha temporária (o app só prepara o texto; quem envia é ele). */
export function temporaryPasswordMessage(name: string, password: string): string {
  const first = name.trim().split(/\s+/)[0] || name
  // A senha vai sozinha numa linha: assim um toque duplo seleciona só ela (sem ponto ou espaço grudado).
  return `Olá, ${first}! Sua senha temporária para entrar no Pelo & Ponto é:

${password}

Ao entrar, o app pede para você criar uma nova senha.`
}
