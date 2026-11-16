import { useMemo, useState } from 'react'
import { ApiError } from '../../api/client'
import { longDate, todayIn } from '../../api/dates'
import { describeError } from '../../api/messages'
import type { CatalogProvider, CatalogService, FreeSlot } from '../../api/booking'
import { useSingleFlight } from '../useSingleFlight'
import { DayStep, SlotStep } from './BookingSteps'

/** Recusas em que o problema é o HORÁRIO escolhido: a caixa continua aberta, mostra o motivo e refaz a lista de horários. */
const SLOT_REASONS = new Set(['SLOT_TAKEN', 'IN_THE_PAST', 'TOO_SOON', 'OUTSIDE_AVAILABILITY', 'NO_CHANGE', 'NONEXISTENT_LOCAL_TIME', 'AMBIGUOUS_LOCAL_TIME'])

/**
 * Remarcar: o cliente escolhe outro dia e horário para o MESMO agendamento (mesmo serviço, mesma duração, mesmo pet). Os horários livres
 * ignoram o próprio agendamento, então dá para deslocá-lo para um horário que o sobreponha. As regras (prazo, limite de 3 remarcações,
 * expediente, antecedência, horário ocupado) são da API; as recusas aparecem aqui com o motivo. Recusa por causa do HORÁRIO mantém a caixa
 * aberta; qualquer outra (prazo, limite, agendamento que mudou) fecha a caixa e entrega o motivo à lista.
 */
/** O agendamento que se remarca: o que a tela já sabe dele (cliente e equipe têm formatos diferentes de lista). */
export interface RescheduleTarget {
  id: string
  providerId: string
  serviceId: string
  serviceName: string | null
  businessName: string | null
  startsAt: string
  endsAt: string
  timeZone: string
}

export function RescheduleBox<T extends { startsAt: string; endsAt: string }>({
  item,
  reschedule,
  hint,
  onRescheduled,
  onClose,
  onRefused,
}: {
  item: RescheduleTarget
  /** Faz a chamada à API (cliente e equipe usam a mesma rota). */
  reschedule: (localStart: string) => Promise<T>
  /** Texto de apoio: o que vale para quem remarca (limite e prazo do cliente; histórico da equipe). */
  hint: string
  /** O agendamento já movido, como a API devolveu. */
  onRescheduled: (updated: T) => Promise<void>
  onClose: () => void
  /** A API recusou por um motivo que não é o horário: a lista mostra a mensagem e se refaz (o agendamento pode ter mudado). */
  onRefused: (message: string, error: unknown) => Promise<void>
}) {
  const zone = item.timeZone
  const provider = useMemo<CatalogProvider>(() => ({ id: item.providerId, businessName: item.businessName ?? '', timeZone: zone }), [item.providerId, item.businessName, zone])
  const service = useMemo<CatalogService>(
    () => ({ id: item.serviceId, name: item.serviceName ?? 'Serviço', durationMinutes: Math.round((Date.parse(item.endsAt) - Date.parse(item.startsAt)) / 60000) }),
    [item.serviceId, item.serviceName, item.startsAt, item.endsAt],
  )
  const [date, setDate] = useState(() => {
    const current = todayIn(zone, new Date(item.startsAt))
    const today = todayIn(zone)
    return current >= today ? current : today
  })
  const [slot, setSlot] = useState<FreeSlot | null>(null)
  const [slotsVersion, setSlotsVersion] = useState(0)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const once = useSingleFlight()

  async function confirm() {
    if (!slot) return
    const sent = slot // o que está sendo enviado, copiado no instante do envio
    await once(async () => {
      setError(null)
      setBusy(true)
      try {
        const updated = await reschedule(sent.localStart)
        await onRescheduled(updated)
      } catch (e) {
        if (e instanceof ApiError && e.reason && SLOT_REASONS.has(e.reason)) {
          setError(describeError(e))
          setSlot(null)
          setSlotsVersion((v) => v + 1)
        } else {
          await onRefused(describeError(e), e)
        }
      } finally {
        setBusy(false)
      }
    })
  }

  return (
    <section className="step reschedule" aria-label="Remarcar agendamento">
      <h2>Remarcar para qual dia e horário?</h2>
      <p className="hint">{hint}</p>
      <p role="alert" className="error" hidden={!error}>
        {error}
      </p>
      <DayStep
        disabled={busy}
        provider={provider}
        date={date}
        onChange={(d) => {
          setDate(d)
          setSlot(null)
          setError(null)
        }}
      />
      <SlotStep
        key={`${date}|${slotsVersion}`}
        provider={provider}
        service={service}
        date={date}
        disabled={busy}
        selected={slot}
        excludeAppointmentId={item.id}
        onSelect={(s) => {
          setSlot(s)
          setError(null)
        }}
      />
      {slot && (
        <p>
          Remarcar para <strong>{longDate(slot.localStart.slice(0, 10))}</strong>, às <strong>{slot.localStart.slice(11, 16)}</strong> (horário do negócio).
        </p>
      )}
      <span className="actions">
        <button type="button" className="button" disabled={busy || !slot} onClick={confirm}>
          {busy ? 'Remarcando…' : 'Confirmar remarcação'}
        </button>
        <button type="button" className="button secondary" disabled={busy} onClick={onClose}>
          Voltar
        </button>
      </span>
    </section>
  )
}
