import { useCallback, useMemo, useState } from 'react'
import { Link } from 'react-router-dom'
import { agendaApi, type AgendaItem, type Workplace } from '../../api/agenda'
import { addDays, isDate, longDate, timeIn, todayIn } from '../../api/dates'
import { ApiError } from '../../api/client'
import { isNotConfigured } from '../../api/errors'
import { describeError } from '../../api/messages'
import { useAuth } from '../../auth/session'
import { RefreshWarning } from '../RefreshWarning'
import { useLoad } from '../useLoad'
import { useSingleFlight } from '../useSingleFlight'

/** Agenda do dia do prestador, para o dono e para o atendente vinculado. O fuso e o negócio vêm do servidor (o atendente não os conhece). */
export function AgendaPage() {
  const { request } = useAuth()
  const api = useMemo(() => agendaApi(request), [request])
  const load = useCallback(() => api.workplace(), [api])
  const { data: workplace, error, loading } = useLoad<Workplace>(load)

  if (loading && !workplace) return <p role="status">Carregando…</p>
  if (isNotConfigured(error)) {
    return (
      <section className="card">
        <h1>Agenda</h1>
        <p className="notice">
          Cadastre primeiro o seu negócio em <Link to="/meu-negocio">Meu negócio</Link>.
        </p>
      </section>
    )
  }
  if (error instanceof ApiError && error.status === 403) {
    return (
      <section className="card">
        <h1>Agenda</h1>
        <p className="notice">Você ainda não está vinculado a um prestador. Peça ao prestador para vincular a sua conta à equipe dele.</p>
      </section>
    )
  }
  if (error && !workplace) return <p role="alert" className="error">{describeError(error)}</p>
  if (!workplace) return null
  return <Day workplace={workplace} />
}

function Day({ workplace }: { workplace: Workplace }) {
  const [date, setDate] = useState(() => todayIn(workplace.timeZone))
  return (
    <section className="card">
      <h1>Agenda</h1>
      <p className="hint">
        {workplace.businessName} · horários no fuso {workplace.timeZone}
      </p>
      <div className="daybar">
        <button type="button" className="button secondary" onClick={() => setDate((d) => addDays(d, -1))}>
          Dia anterior
        </button>
        <button type="button" className="button secondary" onClick={() => setDate(todayIn(workplace.timeZone))}>
          Hoje
        </button>
        <button type="button" className="button secondary" onClick={() => setDate((d) => addDays(d, 1))}>
          Próximo dia
        </button>
        <label>
          Data
          <input
            type="date"
            value={date}
            onChange={(e) => {
              if (isDate(e.target.value)) setDate(e.target.value)
            }}
          />
        </label>
      </div>
      <h2>{longDate(date)}</h2>
      {/* A chave é a data: trocar de dia refaz a lista do zero (nunca aparece a lista de outro dia sob a data nova). */}
      <DayList key={date} date={date} timeZone={workplace.timeZone} />
    </section>
  )
}

function DayList({ date, timeZone }: { date: string; timeZone: string }) {
  const { request } = useAuth()
  const api = useMemo(() => agendaApi(request), [request])
  const load = useCallback(() => api.day(date), [api, date])
  const { data, error, loading, reload } = useLoad<AgendaItem[]>(load)
  const [notice, setNotice] = useState<string | null>(null)
  // Cancelados com SUCESSO nesta tela: somem da lista na hora, mesmo que o GET seguinte falhe (a lista antiga ainda os mostraria
  // como "Agendado" com o botão de cancelar, que só receberia 409).
  const [cancelled, setCancelled] = useState<ReadonlySet<string>>(new Set())

  if (loading && !data) return <p role="status">Carregando…</p>
  if (error && !data) return <p role="alert" className="error">{describeError(error)}</p>
  const visible = data?.filter((item) => !cancelled.has(item.id))
  return (
    <>
      {error !== undefined && data && <RefreshWarning onRetry={reload} />}
      <p role="status" className="notice" hidden={!notice}>
        {notice}
      </p>
      {visible && visible.length === 0 && <p className="notice">Nenhum agendamento neste dia.</p>}
      <ul className="rows">
        {visible?.map((item) => (
          <AppointmentRow
            key={item.id}
            item={item}
            timeZone={timeZone}
            onChanged={reload}
            onCancelled={() => setCancelled((current) => new Set(current).add(item.id))}
            setNotice={setNotice}
          />
        ))}
      </ul>
    </>
  )
}

const STATUS_LABEL: Record<string, string> = { SCHEDULED: 'Agendado', CHECKED_IN: 'Chegou', COMPLETED: 'Concluído' }

function AppointmentRow({ item, timeZone, onChanged, onCancelled, setNotice }: { item: AgendaItem; timeZone: string; onChanged: () => Promise<void>; onCancelled: () => void; setNotice: (message: string | null) => void }) {
  const { request } = useAuth()
  const api = useMemo(() => agendaApi(request), [request])
  const [confirming, setConfirming] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const once = useSingleFlight()
  const customer = item.customerName ?? 'Cliente'

  async function cancel() {
    await once(async () => {
      setError(null)
      setNotice(null)
      setBusy(true)
      try {
        await api.cancel(item.id)
        onCancelled() // some da lista agora; o GET seguinte só confirma
        setNotice(`Agendamento de ${customer} cancelado.`)
        await onChanged()
      } catch (e) {
        setError(describeError(e))
        setConfirming(false)
      } finally {
        setBusy(false)
      }
    })
  }

  return (
    <li className="row">
      <span>
        <strong>
          {timeIn(item.startsAt, timeZone)}–{timeIn(item.endsAt, timeZone)}
        </strong>{' '}
        · {item.serviceName ?? 'Serviço'} · {customer} <span className="badge">{STATUS_LABEL[item.status] ?? item.status}</span>
      </span>
      {item.status === 'SCHEDULED' && (
        <span className="actions">
          {!confirming ? (
            <button type="button" className="button secondary" disabled={busy} onClick={() => setConfirming(true)}>
              Cancelar agendamento
            </button>
          ) : (
            <>
              <span>Cancelar o agendamento de {customer}?</span>
              <button type="button" className="button" disabled={busy} onClick={cancel}>
                {busy ? 'Cancelando…' : 'Confirmar cancelamento'}
              </button>
              <button type="button" className="button secondary" disabled={busy} onClick={() => setConfirming(false)}>
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
