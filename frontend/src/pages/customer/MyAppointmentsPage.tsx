import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { Link } from 'react-router-dom'
import { ApiError } from '../../api/client'
import { longDate, timeIn, todayIn } from '../../api/dates'
import { describeError } from '../../api/messages'
import { bookingApi, type BookedAppointment } from '../../api/booking'
import { formatMoney } from '../../api/money'
import { SPECIES_LABEL } from '../../api/pets'
import { useAuth } from '../../auth/session'
import { RefreshWarning } from '../RefreshWarning'
import { useLoad } from '../useLoad'
import { useSingleFlight } from '../useSingleFlight'
import { Loading } from '../Loading'
import { AppointmentHistory } from './AppointmentHistory'
import { RescheduleBox } from './RescheduleBox'
import { PetAvatar } from '../../ui/components'

const PAGE_SIZE = 50
const STATUS_LABEL: Record<string, string> = { SCHEDULED: 'Agendado', CHECKED_IN: 'Chegou', COMPLETED: 'Concluído', CANCELLED: 'Cancelado' }

/** Motivos em que o agendamento JÁ mudou no servidor: a lista precisa ser refeita, não só mostrar o erro. */
const STATE_CHANGED = new Set(['ALREADY_CANCELLED', 'NOT_CANCELLABLE', 'NOT_RESCHEDULABLE', 'CONCURRENT_UPDATE'])

/**
 * Meus agendamentos: os do próprio cliente (a API usa a identidade do token), do mais novo para o mais antigo, com data e hora no
 * fuso do negócio. Cancelar pede confirmação; as recusas da API (prazo, já cancelado, alteração simultânea) mostram o motivo. Remarcar abre a
 * escolha de outro dia e horário para o MESMO agendamento; o agendamento já movido (como a API devolveu) fica na tela mesmo que a lista seguinte
 * falhe, e vale só até uma carga NOVA da lista chegar. O histórico do agendamento abre sob demanda.
 */
export function MyAppointmentsPage() {
  const { request } = useAuth()
  const api = useMemo(() => bookingApi(request), [request])
  const load = useCallback(() => api.mine(0, PAGE_SIZE), [api])
  const { data, error, loading, reload, version } = useLoad<BookedAppointment[]>(load)
  const [notice, setNotice] = useState<string | null>(null)
  // Cancelados com SUCESSO nesta tela: aparecem como "Cancelado" na hora, mesmo que o GET seguinte falhe.
  const [cancelled, setCancelled] = useState<ReadonlySet<string>>(new Set())
  // Remarcados com SUCESSO nesta tela: o agendamento como a API devolveu, válido só até uma carga NOVA da lista (version) substituí-lo.
  const [moved, setMoved] = useState<ReadonlyMap<string, { item: BookedAppointment; version: number }>>(new Map())

  if (loading && !data) return <Loading />
  if (error && !data) return <p role="alert" className="error">{describeError(error)}</p>
  return (
    <section className="card">
      <h1>Meus agendamentos</h1>
      {error !== undefined && data && <RefreshWarning onRetry={reload} />}
      <p role="status" className="notice" hidden={!notice}>
        {notice}
      </p>
      {data && data.length === 0 && (
        <p className="notice">
          Você ainda não tem agendamentos. <Link to="/agendar">Agendar um horário</Link>
        </p>
      )}
      <ul className="rows">
        {data?.map((item) => (
          <Row
            key={item.id}
            item={moved.get(item.id)?.version === version ? moved.get(item.id)!.item : item}
            cancelled={cancelled.has(item.id)}
            onRescheduled={async (updated) => {
              setMoved((current) => new Map(current).set(item.id, { item: updated, version }))
              await reload()
            }}
            onCancelled={() => setCancelled((current) => new Set(current).add(item.id))}
            onStateChanged={reload}
            setNotice={setNotice}
          />
        ))}
      </ul>
      {data && data.length === PAGE_SIZE && <p className="hint">Mostrando os {PAGE_SIZE} agendamentos mais recentes.</p>}
    </section>
  )
}

function Row({
  item,
  cancelled,
  onCancelled,
  onRescheduled,
  onStateChanged,
  setNotice,
}: {
  item: BookedAppointment
  cancelled: boolean
  onCancelled: () => void
  onRescheduled: (updated: BookedAppointment) => Promise<void>
  onStateChanged: () => Promise<void>
  setNotice: (message: string | null) => void
}) {
  const { request } = useAuth()
  const api = useMemo(() => bookingApi(request), [request])
  const [confirming, setConfirming] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [rescheduling, setRescheduling] = useState(false)
  const [historyOpen, setHistoryOpen] = useState(false)
  const once = useSingleFlight()
  // Foco (teclado e leitor de tela): ao abrir a confirmação vai para "Voltar" (a opção segura); ao fechar volta para "Cancelar agendamento".
  const rowRef = useRef<HTMLLIElement>(null)
  const openRef = useRef<HTMLButtonElement>(null)
  const backRef = useRef<HTMLButtonElement>(null)
  const wasConfirming = useRef(false)
  const rescheduleRef = useRef<HTMLButtonElement>(null)
  const wasRescheduling = useRef(false)
  useEffect(() => {
    if (!rescheduling && wasRescheduling.current) rescheduleRef.current?.focus()
    wasRescheduling.current = rescheduling
  }, [rescheduling])
  useEffect(() => {
    if (confirming) backRef.current?.focus()
    else if (wasConfirming.current) openRef.current?.focus()
    wasConfirming.current = confirming
  }, [confirming])
  const zone = item.timeZone ?? 'UTC'
  const status = cancelled ? 'CANCELLED' : item.status
  const what = `${item.serviceName ?? 'Serviço'} em ${item.businessName ?? 'o negócio'}`

  async function cancel() {
    await once(async () => {
      setError(null)
      setNotice(null)
      setBusy(true)
      try {
        await api.cancel(item.id)
        onCancelled()
        setConfirming(false)
        // O botão some: leva o foco para a própria linha, que passa a mostrar "Cancelado".
        wasConfirming.current = false
        rowRef.current?.focus()
        setNotice(`Agendamento cancelado: ${what}.`)
        await onStateChanged()
      } catch (e) {
        setError(describeError(e))
        setConfirming(false)
        // O agendamento mudou no servidor (já cancelado, não cancelável, alterado por outra pessoa): refaz a lista.
        if (e instanceof ApiError && e.reason && STATE_CHANGED.has(e.reason)) await onStateChanged().catch(() => undefined)
      } finally {
        setBusy(false)
      }
    })
  }

  async function rescheduled(updated: BookedAppointment) {
    setError(null)
    setRescheduling(false)
    wasRescheduling.current = false // a caixa fecha com sucesso: o foco vai para a linha, que mostra o horário novo
    rowRef.current?.focus()
    setNotice(`Agendamento remarcado: ${what}, ${longDate(todayIn(zone, new Date(updated.startsAt)))}, às ${timeIn(updated.startsAt, zone)}.`)
    await onRescheduled(updated)
  }

  async function refused(message: string, e: unknown) {
    setNotice(null)
    setError(message)
    setRescheduling(false)
    wasRescheduling.current = false
    rowRef.current?.focus()
    // O agendamento mudou no servidor (cancelado, já começou, alterado por outra pessoa): refaz a lista.
    if (e instanceof ApiError && e.reason && STATE_CHANGED.has(e.reason)) await onStateChanged().catch(() => undefined)
  }

  return (
    <li className="row" ref={rowRef} tabIndex={-1}>
      {item.pet && <PetAvatar species={item.pet.species} />}
      <span>
        <strong>{longDate(todayIn(zone, new Date(item.startsAt)))}</strong>, {timeIn(item.startsAt, zone)}–{timeIn(item.endsAt, zone)} · {what}
        {item.pet && (
          <>
            {' '}
            · {item.pet.name} ({SPECIES_LABEL[item.pet.species] ?? item.pet.species})
          </>
        )}
        {item.priceCents != null && <> · {formatMoney(item.priceCents)}</>}{' '}
        <span className="badge" data-status={status}>{STATUS_LABEL[status] ?? status}</span>
      </span>
      {status === 'SCHEDULED' && (
        <span className="actions">
          {!confirming ? (
            <>
              {item.timeZone && (
                <button type="button" className="button secondary" ref={rescheduleRef} disabled={busy} aria-expanded={rescheduling} onClick={() => setRescheduling((open) => !open)}>
                  Remarcar
                </button>
              )}
              <button type="button" className="button secondary" ref={openRef} disabled={busy} onClick={() => setConfirming(true)}>
                Cancelar agendamento
              </button>
            </>
          ) : (
            <>
              <span>Cancelar {what}?</span>
              <button type="button" className="button" disabled={busy} onClick={cancel}>
                {busy ? 'Cancelando…' : 'Confirmar cancelamento'}
              </button>
              <button type="button" className="button secondary" ref={backRef} disabled={busy} onClick={() => setConfirming(false)}>
                Voltar
              </button>
            </>
          )}
        </span>
      )}
      {error && (
        <p role="alert" className="error">
          {error}
        </p>
      )}
      <span className="row-extra">
        <button type="button" className="button secondary" aria-expanded={historyOpen} onClick={() => setHistoryOpen((open) => !open)}>
          {historyOpen ? 'Ocultar histórico' : 'Histórico'}
        </button>
      </span>
      {rescheduling && status === 'SCHEDULED' && (
        <div className="row-extra">
          <RescheduleBox
            item={{ ...item, timeZone: zone }}
            reschedule={(localStart) => api.reschedule(item.id, localStart)}
            hint="O agendamento continua o mesmo: só muda o horário. Até 3 remarcações, dentro do prazo do estúdio."
            onRescheduled={rescheduled}
            onClose={() => setRescheduling(false)}
            onRefused={refused}
          />
        </div>
      )}
      {historyOpen && (
        <div className="row-extra">
          <AppointmentHistory key={item.startsAt + status} appointmentId={item.id} timeZone={zone} />
        </div>
      )}
    </li>
  )
}
