import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { Link } from 'react-router-dom'
import { ApiError } from '../../api/client'
import { longDate, timeIn, todayIn } from '../../api/dates'
import { describeError } from '../../api/messages'
import { bookingApi, type BookedAppointment } from '../../api/booking'
import { useAuth } from '../../auth/session'
import { RefreshWarning } from '../RefreshWarning'
import { useLoad } from '../useLoad'
import { useSingleFlight } from '../useSingleFlight'
import { Loading } from '../Loading'

const PAGE_SIZE = 50
const STATUS_LABEL: Record<string, string> = { SCHEDULED: 'Agendado', CHECKED_IN: 'Chegou', COMPLETED: 'Concluído', CANCELLED: 'Cancelado' }

/** Motivos em que o agendamento JÁ mudou no servidor: a lista precisa ser refeita, não só mostrar o erro. */
const STATE_CHANGED = new Set(['ALREADY_CANCELLED', 'NOT_CANCELLABLE', 'CONCURRENT_UPDATE'])

/**
 * Meus agendamentos: os do próprio cliente (a API usa a identidade do token), do mais novo para o mais antigo, com data e hora no
 * fuso do negócio. Cancelar pede confirmação; as recusas da API (prazo, já cancelado, alteração simultânea) mostram o motivo.
 */
export function MyAppointmentsPage() {
  const { request } = useAuth()
  const api = useMemo(() => bookingApi(request), [request])
  const load = useCallback(() => api.mine(0, PAGE_SIZE), [api])
  const { data, error, loading, reload } = useLoad<BookedAppointment[]>(load)
  const [notice, setNotice] = useState<string | null>(null)
  // Cancelados com SUCESSO nesta tela: aparecem como "Cancelado" na hora, mesmo que o GET seguinte falhe.
  const [cancelled, setCancelled] = useState<ReadonlySet<string>>(new Set())

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
            item={item}
            cancelled={cancelled.has(item.id)}
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
  onStateChanged,
  setNotice,
}: {
  item: BookedAppointment
  cancelled: boolean
  onCancelled: () => void
  onStateChanged: () => Promise<void>
  setNotice: (message: string | null) => void
}) {
  const { request } = useAuth()
  const api = useMemo(() => bookingApi(request), [request])
  const [confirming, setConfirming] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const once = useSingleFlight()
  // Foco (teclado e leitor de tela): ao abrir a confirmação vai para "Voltar" (a opção segura); ao fechar volta para "Cancelar agendamento".
  const rowRef = useRef<HTMLLIElement>(null)
  const openRef = useRef<HTMLButtonElement>(null)
  const backRef = useRef<HTMLButtonElement>(null)
  const wasConfirming = useRef(false)
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

  return (
    <li className="row" ref={rowRef} tabIndex={-1}>
      <span>
        <strong>{longDate(todayIn(zone, new Date(item.startsAt)))}</strong>, {timeIn(item.startsAt, zone)}–{timeIn(item.endsAt, zone)} · {what}{' '}
        <span className="badge" data-status={status}>{STATUS_LABEL[status] ?? status}</span>
      </span>
      {status === 'SCHEDULED' && (
        <span className="actions">
          {!confirming ? (
            <button type="button" className="button secondary" ref={openRef} disabled={busy} onClick={() => setConfirming(true)}>
              Cancelar agendamento
            </button>
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
    </li>
  )
}
