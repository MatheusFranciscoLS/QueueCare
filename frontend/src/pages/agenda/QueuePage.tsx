import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { Link } from 'react-router-dom'
import { agendaApi, type QueueItem, type Workplace } from '../../api/agenda'
import { ApiError } from '../../api/client'
import { timeIn } from '../../api/dates'
import { isNotConfigured } from '../../api/errors'
import { describeError } from '../../api/messages'
import { useAuth } from '../../auth/session'
import { RefreshWarning } from '../RefreshWarning'
import { useLoad } from '../useLoad'
import { useSingleFlight } from '../useSingleFlight'
import { Loading } from '../Loading'

/** Fila de hoje do prestador, para o dono e para o atendente vinculado: quem já chegou e espera, por ordem de chegada. */
export function QueuePage() {
  const { request } = useAuth()
  const api = useMemo(() => agendaApi(request), [request])
  const load = useCallback(() => api.workplace(), [api])
  const { data: workplace, error, loading } = useLoad<Workplace>(load)

  if (loading && !workplace) return <Loading />
  if (isNotConfigured(error)) {
    return (
      <section className="card">
        <h1>Fila</h1>
        <p className="notice">
          Cadastre primeiro o seu negócio em <Link to="/meu-negocio">Meu negócio</Link>.
        </p>
      </section>
    )
  }
  if (error instanceof ApiError && error.status === 403) {
    return (
      <section className="card">
        <h1>Fila</h1>
        <p className="notice">Você ainda não está vinculado a um prestador. Peça ao prestador para vincular a sua conta à equipe dele.</p>
      </section>
    )
  }
  if (error && !workplace) return <p role="alert" className="error">{describeError(error)}</p>
  if (!workplace) return null
  return <Queue workplace={workplace} />
}

/** Motivos em que o agendamento JÁ mudou no servidor: a fila é refeita. */
const STATE_CHANGED = new Set(['ALREADY_CANCELLED', 'NOT_CANCELLABLE', 'INVALID_STATE', 'CONCURRENT_UPDATE'])

function Queue({ workplace }: { workplace: Workplace }) {
  const { request } = useAuth()
  const api = useMemo(() => agendaApi(request), [request])
  const load = useCallback(() => api.queue(), [api])
  const { data, error, loading, reload } = useLoad<QueueItem[]>(load)
  const [notice, setNotice] = useState<string | null>(null)
  // O erro mora AQUI, não na linha: se o servidor já mudou e a fila é refeita, a linha some e levaria o aviso junto.
  const [problem, setProblem] = useState<string | null>(null)
  // Concluir REMOVE a linha: o foco vai para o aviso do resultado, que continua montado (focar a linha que some perderia o foco).
  const noticeRef = useRef<HTMLParagraphElement>(null)
  const focusNotice = useRef(false)
  useEffect(() => {
    if (notice && focusNotice.current) {
      focusNotice.current = false
      noticeRef.current?.focus()
    }
  }, [notice])
  // Concluídos com SUCESSO nesta tela: saem da fila na hora, mesmo que o GET seguinte falhe ou traga uma fila de antes da conclusão.
  const [done, setDone] = useState<ReadonlySet<string>>(new Set())

  if (loading && !data) return <Loading />
  if (error && !data) return <p role="alert" className="error">{describeError(error)}</p>
  const visible = data?.filter((item) => !done.has(item.id))
  return (
    <section className="card">
      <h1>Fila</h1>
      <p className="hint">
        {workplace.businessName} · quem já chegou hoje e espera, por ordem de chegada · horários no fuso {workplace.timeZone}
      </p>
      <p>
        <button type="button" className="button secondary" onClick={() => void reload()}>
          Atualizar a fila
        </button>
      </p>
      {error !== undefined && data && <RefreshWarning onRetry={reload} />}
      <p role="status" className="notice" ref={noticeRef} tabIndex={-1} hidden={!notice}>
        {notice}
      </p>
      <p role="alert" className="error" hidden={!problem}>
        {problem}
      </p>
      {visible && visible.length === 0 && <p className="notice">Ninguém na fila agora.</p>}
      <ol className="rows timeline">
        {visible?.map((item, index) => (
          <QueueRow
            key={item.id}
            item={item}
            position={index + 1}
            timeZone={workplace.timeZone}
            onDone={() => {
              focusNotice.current = true
              setDone((current) => new Set(current).add(item.id))
            }}
            onChanged={reload}
            setNotice={setNotice}
            setProblem={setProblem}
          />
        ))}
      </ol>
    </section>
  )
}

function QueueRow({
  item,
  position,
  timeZone,
  onDone,
  onChanged,
  setNotice,
  setProblem,
}: {
  item: QueueItem
  position: number
  timeZone: string
  onDone: () => void
  onChanged: () => Promise<void>
  setNotice: (message: string | null) => void
  setProblem: (message: string | null) => void
}) {
  const { request } = useAuth()
  const api = useMemo(() => agendaApi(request), [request])
  const [busy, setBusy] = useState(false)
  const once = useSingleFlight()
  const customer = item.customerName ?? 'Cliente'

  async function complete() {
    await once(async () => {
      setProblem(null)
      setNotice(null)
      setBusy(true)
      try {
        await api.complete(item.id)
        onDone() // sai da fila agora; o GET seguinte só confirma
        setNotice(`Atendimento de ${customer} concluído.`)
        await onChanged()
      } catch (e) {
        setProblem(describeError(e))
        if (e instanceof ApiError && e.reason && STATE_CHANGED.has(e.reason)) await onChanged().catch(() => undefined)
      } finally {
        setBusy(false)
      }
    })
  }

  return (
    <li className="row appt" data-status="CHECKED_IN">
      <span className="appt-info">
        <strong className="appt-time">{position}º</strong>
        <span className="sep" aria-hidden="true"> · </span>
        <span className="appt-who">{customer}</span>
        <span className="sep" aria-hidden="true"> · </span>
        <span className="appt-service">{item.serviceName ?? 'Serviço'}</span>
        <span className="sep" aria-hidden="true"> · </span>
        <span className="badge" data-status="CHECKED_IN">chegou às {timeIn(item.checkedInAt, timeZone)}</span>
      </span>
      <span className="actions">
        <button type="button" className="button" disabled={busy} onClick={complete}>
          {busy ? 'Enviando…' : 'Concluir atendimento'}
        </button>
      </span>
    </li>
  )
}
