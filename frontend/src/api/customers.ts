import type { AuthApi } from '../auth/session'
import type { CatalogService, FreeSlot } from './booking'
import type { ImportRow } from './importCsv'
import { formatMoney } from './money'
import { petBody, type Pet, type PetInput } from './pets'

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
  /** Cadastrado pela equipe, pelo balcão (ADR 0033): só estes a equipe agenda e cadastra pet. */
  byCounter?: boolean
  /** Falso = sem e-mail: não há como entrar no app (e redefinir a senha não adianta). */
  hasEmail?: boolean
}

/** O tutor recém-cadastrado pelo balcão. */
export interface CounterCustomer {
  id: string
  name: string
  phone: string
  hasEmail: boolean
}

/** O resultado de uma importação (ADR 0034): só números e o motivo de cada linha, nunca o que veio na planilha. */
export interface ImportResult {
  created: number
  duplicates: number
  invalid: number
  emailTaken: number
  petsCreated: number
  petsSkipped: number
  rows: { row: number; status: 'CREATED' | 'DUPLICATE_PHONE' | 'INVALID' | 'EMAIL_TAKEN'; petCreated: boolean; petSkipped: boolean }[]
}

/** A reserva feita pelo balcão. O sinal por Pix, quando pedido, fica pendente até a equipe marcar como recebido. */
export interface CounterAppointment {
  id: string
  startsAt: string
  endsAt: string
  status: string
  priceCents?: number | null
  depositCents?: number | null
  depositStatus: string
}

type Request = AuthApi['request']

/** Clientes do estúdio: a busca é da equipe (dono e atendente); a senha temporária é só do dono. */
export function customersApi(request: Request) {
  return {
    search: (q: string) => request<StudioCustomer[]>(`/api/agenda/customers?q=${encodeURIComponent(q)}`),
    /** A senha aparece SÓ nesta resposta; o tutor terá de trocá-la ao entrar. */
    /** Cadastra o tutor (nome e WhatsApp obrigatórios; e-mail opcional). A conta nasce sem acesso ao app. */
    createCustomer: (input: { name: string; phone: string; email: string }) =>
      request<CounterCustomer>('/api/agenda/customers', { method: 'POST', body: { name: input.name, phone: input.phone, email: input.email.trim() === '' ? null : input.email } }),
    pets: (customerId: string) => request<Pet[]>(`/api/agenda/customers/${encodeURIComponent(customerId)}/pets`),
    createPet: (customerId: string, input: PetInput) => request<Pet>(`/api/agenda/customers/${encodeURIComponent(customerId)}/pets`, { method: 'POST', body: petBody(input) }),
    /** Os serviços do estúdio de quem pergunta (o catálogo é só do tutor). */
    services: () => request<CatalogService[]>('/api/agenda/services'),
    slots: (customerId: string, serviceId: string, date: string, petId?: string) =>
      request<FreeSlot[]>(`/api/agenda/customers/${encodeURIComponent(customerId)}/slots?serviceId=${encodeURIComponent(serviceId)}&date=${encodeURIComponent(date)}` + (petId ? `&petId=${encodeURIComponent(petId)}` : '')),
    book: (customerId: string, input: { serviceId: string; localStart: string; petId: string }) =>
      request<CounterAppointment>(`/api/agenda/customers/${encodeURIComponent(customerId)}/appointments`, { method: 'POST', body: input }),
    /** Os dados do tutor do balcão, para o dono entregar a ele (ADR 0033): o tutor sem acesso ao app não baixa sozinho. */
    exportData: (customerId: string) => request<unknown>(`/api/provider/customers/${encodeURIComponent(customerId)}/export`),
    /** Exclui (anonimiza) o cadastro a pedido do tutor; só vale enquanto ele nunca assumiu a conta (409 CUSTOMER_HAS_ACCESS). */
    anonymize: (customerId: string) => request<void>(`/api/provider/customers/${encodeURIComponent(customerId)}/anonymize`, { method: 'POST' }),
    /** Importa até 200 linhas de uma planilha (só o dono). Cada linha é independente: as ruins são relatadas e as outras entram. */
    importRows: (rows: ImportRow[]) => request<ImportResult>('/api/agenda/customers/import', { method: 'POST', body: { rows: rows.map((r) => ({ name: r.name, phone: r.phone, email: r.email === '' ? null : r.email, petName: r.petName === '' ? null : r.petName, petSpecies: r.petSpecies })) } }),
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

/** A mensagem de confirmação que a equipe manda depois de agendar pelo balcão (o app só prepara o texto; quem envia é a equipe, pelo WhatsApp). */
export function counterBookingMessage(input: { tutorName: string; studioName: string; petName: string; when: string; depositCents: number | null }): string {
  const first = input.tutorName.trim().split(/\s+/)[0] || input.tutorName
  const deposit = input.depositCents == null ? '' : ` Para garantir o horário, o estúdio vai combinar com você o sinal de ${formatMoney(input.depositCents)} por Pix.`
  return `Olá, ${first}! Seu horário no ${input.studioName} está marcado para ${input.when}, para ${input.petName}.${deposit} Se precisar mudar, é só avisar por aqui.`
}
