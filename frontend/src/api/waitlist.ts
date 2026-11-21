import type { AuthApi } from '../auth/session'
import { firstName } from './policy'
import { whatsappLink } from './phone'
import { longDate, timeIn, todayIn } from './dates'

/** Lista de espera (ADR 0022): o tutor pede "avise-me se vagar um horário neste dia"; a equipe vê quem espera e chama pelo WhatsApp. */

/** Entrada vista pelo tutor. `date` é o dia pedido (AAAA-MM-DD, no fuso do estúdio). */
export interface WaitlistMine {
  id: string
  providerId: string
  businessName: string
  serviceName: string | null
  petName: string | null
  date: string
}

/** Entrada vista pela equipe: com contato e a duração prevista PARA ESSE PET (ADR 0020). `calledAt`: quando a equipe chamou. */
export interface WaitlistStaff {
  id: string
  customerId: string
  customerName: string | null
  customerPhone: string | null
  petName: string | null
  petSpecies: string | null
  serviceName: string | null
  durationMinutes: number
  createdAt: string
  calledAt: string | null
}

type Request = AuthApi['request']

export function waitlistApi(request: Request) {
  return {
    join: (input: { providerId: string; serviceId: string; petId: string; date: string }) => request<{ id: string }>('/api/waitlist', { method: 'POST', body: input }),
    mine: () => request<WaitlistMine[]>('/api/waitlist/mine'),
    remove: (id: string) => request<void>(`/api/waitlist/${encodeURIComponent(id)}`, { method: 'DELETE' }),
    /** Equipe: quem espera um dia, por ordem de entrada. */
    day: (date: string) => request<WaitlistStaff[]>(`/api/agenda/waitlist?date=${encodeURIComponent(date)}`),
    called: (id: string) => request<unknown>(`/api/waitlist/${encodeURIComponent(id)}/called`, { method: 'POST' }),
  }
}

/**
 * Mensagem da equipe para quem espera: avisa que vagou um horário e pergunta se ainda tem interesse (sem artigos antes de nomes, que errariam
 * o gênero). O horário exato a equipe combina na conversa: a lista só junta as pessoas.
 */
export function waitlistCallMessage(entry: Pick<WaitlistStaff, 'customerName' | 'serviceName' | 'petName' | 'durationMinutes'>, studioName: string, date: string): string {
  const hello = firstName(entry.customerName) ? `Olá, ${firstName(entry.customerName)}!` : 'Olá!'
  const what = [entry.serviceName ?? 'Atendimento', entry.petName ? `de ${entry.petName}` : ''].filter(Boolean).join(' ')
  return `${hello} Aqui é ${studioName}. Vagou um horário em ${longDate(date)}: ${what}, cerca de ${entry.durationMinutes} min. Você ainda tem interesse?`
}

export function waitlistCallLink(entry: WaitlistStaff, studioName: string, date: string): string | null {
  return entry.customerPhone ? whatsappLink(entry.customerPhone, waitlistCallMessage(entry, studioName, date)) : null
}

/** "20/11, 17:02": quando a equipe chamou, no fuso do negócio. */
export function calledStamp(iso: string, timeZone: string): string {
  const day = todayIn(timeZone, new Date(iso))
  return `${day.slice(8, 10)}/${day.slice(5, 7)}, ${timeIn(iso, timeZone)}`
}
