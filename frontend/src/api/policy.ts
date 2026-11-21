import { longDate, timeIn, todayIn } from './dates'
import { whatsappLink } from './phone'

/** Política de cancelamento visível (ADR 0021): o tutor vê o limite ANTES de errar, e depois dele tem um caminho (falar com o estúdio). */

/** "sexta-feira, 21 de novembro, às 18:00", no fuso do negócio. */
export function whenText(instant: string, timeZone: string): string {
  return `${longDate(todayIn(timeZone, new Date(instant)))}, às ${timeIn(instant, timeZone)}`
}

/** Até quando cancelar ou remarcar de graça um atendimento que começa em `startsAt`, dado o prazo do estúdio (em minutos). */
export function freeChangeInstant(startsAt: string, deadlineMinutes: number): string {
  return new Date(Date.parse(startsAt) - deadlineMinutes * 60_000).toISOString()
}

/** Frase do Agendar: "Cancele ou remarque de graça até ...". Prazo zero = até o horário marcado. */
export function bookingPolicyText(startsAt: string, deadlineMinutes: number, timeZone: string): string {
  if (deadlineMinutes <= 0) return 'Você pode cancelar ou remarcar de graça até o horário marcado.'
  return `Você pode cancelar ou remarcar de graça até ${whenText(freeChangeInstant(startsAt, deadlineMinutes), timeZone)}. Depois disso, é só falar com o estúdio.`
}

/** O limite de graça já passou? (A API é quem decide de verdade: isto só escolhe o que mostrar.) */
export function freeChangePassed(freeChangeUntil: string | null | undefined, now: Date = new Date()): boolean {
  return !!freeChangeUntil && Date.parse(freeChangeUntil) <= now.getTime()
}

/** Link do WhatsApp do estúdio com a mensagem pronta para pedir uma mudança depois do prazo. */
export function studioChangeLink(phone: string, petName: string | null | undefined, serviceName: string | null | undefined, startsAt: string, timeZone: string): string {
  const pet = petName ? ` de ${petName}` : ''
  const service = serviceName ? ` (${serviceName})` : ''
  return whatsappLink(phone, `Olá! Preciso mudar o agendamento${pet}${service} marcado para ${whenText(startsAt, timeZone)}. Dá para ajustar?`)
}

/** 0 = "até o horário marcado"; 90 = "1 h 30 min antes"; 720 = "12 h antes"; 2880 = "2 dias antes". Para dica de formulário. */
export function minutesText(minutes: number): string {
  if (!Number.isFinite(minutes) || minutes < 0) return 'valor inválido'
  if (minutes === 0) return 'até o horário marcado'
  if (minutes % 1440 === 0) return minutes === 1440 ? '1 dia antes' : `${minutes / 1440} dias antes`
  const h = Math.floor(minutes / 60)
  const m = minutes % 60
  if (h === 0) return `${m} min antes`
  return m === 0 ? `${h} h antes` : `${h} h ${m} min antes`
}
