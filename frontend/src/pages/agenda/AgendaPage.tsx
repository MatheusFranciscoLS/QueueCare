import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
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
  // O erro mora AQUI, não na linha: se o servidor já mudou e a lista é refeita, a linha pode sumir e levaria o aviso junto.
  const [problem, setProblem] = useState<string | null>(null)
  // Quando a ação REMOVE a linha (cancelar), o foco vai para o aviso do resultado, que continua montado: focar a linha que some perderia o foco.
  const noticeRef = useRef<HTMLParagraphElement>(null)
  const focusNotice = useRef(false)
  useEffect(() => {
    if (notice && focusNotice.current) {
      focusNotice.current = false
      noticeRef.current?.focus()
    }
  }, [notice])
  // Cancelados com SUCESSO nesta tela: somem da lista na hora, mesmo que o GET seguinte falhe (a lista antiga ainda os mostraria
  // como "Agendado" com o botão de cancelar, que só receberia 409).
  const [cancelled, setCancelled] = useState<ReadonlySet<string>>(new Set())
  // Situação confirmada pela API nesta tela (chegou, concluído): vale na hora, mesmo que o GET seguinte falhe ou traga uma lista
  // um pouco antes da mudança. A situação exibida é a MAIS AVANÇADA entre esta e a do servidor (laterOf): uma leitura nova que a confirme
  // ou ultrapasse (outra pessoa concluiu) prevalece, e uma lista de antes da mudança não a desfaz.
  const [statuses, setStatuses] = useState<ReadonlyMap<string, string>>(new Map())
  const setStatus = useCallback((id: string, status: string | null) => {
    setStatuses((current) => {
      const next = new Map(current)
      if (status === null) next.delete(id)
      else next.set(id, status)
      return next
    })
  }, [])

  if (loading && !data) return <p role="status">Carregando…</p>
  if (error && !data) return <p role="alert" className="error">{describeError(error)}</p>
  const visible = data?.filter((item) => !cancelled.has(item.id))
  // Só no dia de HOJE (do negócio) a chegada pode ser registrada; a API também confere (WRONG_DAY).
  const isToday = date === todayIn(timeZone)
  return (
    <>
      {error !== undefined && data && <RefreshWarning onRetry={reload} />}
      <p role="status" className="notice" ref={noticeRef} tabIndex={-1} hidden={!notice}>
        {notice}
      </p>
      <p role="alert" className="error" hidden={!problem}>
        {problem}
      </p>
      {visible && visible.length === 0 && <p className="notice">Nenhum agendamento neste dia.</p>}
      <ul className="rows timeline">
        {visible?.map((item) => (
          <AppointmentRow
            key={item.id}
            item={item}
            status={laterOf(item.status, statuses.get(item.id))}
            timeZone={timeZone}
            canCheckIn={isToday}
            onChanged={reload}
            onCancelled={() => {
              focusNotice.current = true
              setCancelled((current) => new Set(current).add(item.id))
            }}
            onStatus={(status) => setStatus(item.id, status)}
            setNotice={setNotice}
            setProblem={setProblem}
          />
        ))}
      </ul>
    </>
  )
}

/** As situações só andam para frente. */
const RANK: Record<string, number> = { SCHEDULED: 0, CHECKED_IN: 1, COMPLETED: 2 }

/** A situação mais avançada entre a do servidor e a confirmada aqui: uma lista de antes da mudança não a desfaz, e uma leitura nova que a confirme ou ultrapasse (outra pessoa concluiu) prevalece. */
function laterOf(server: string, local: string | undefined): string {
  if (local === undefined || RANK[server] === undefined || RANK[local] === undefined) return server
  return RANK[local] > RANK[server] ? local : server
}

const STATUS_LABEL: Record<string, string> = { SCHEDULED: 'Agendado', CHECKED_IN: 'Chegou', COMPLETED: 'Concluído' }

/** Motivos em que o agendamento JÁ mudou no servidor: a linha volta a valer o que o servidor diz e a lista é refeita. */
const STATE_CHANGED = new Set(['ALREADY_CANCELLED', 'NOT_CANCELLABLE', 'INVALID_STATE', 'CONCURRENT_UPDATE'])

function AppointmentRow({
  item,
  status,
  timeZone,
  canCheckIn,
  onChanged,
  onCancelled,
  onStatus,
  setNotice,
  setProblem,
}: {
  item: AgendaItem
  status: string
  timeZone: string
  canCheckIn: boolean
  onChanged: () => Promise<void>
  onCancelled: () => void
  onStatus: (status: string | null) => void
  setNotice: (message: string | null) => void
  setProblem: (message: string | null) => void
}) {
  const { request } = useAuth()
  const api = useMemo(() => agendaApi(request), [request])
  const [confirming, setConfirming] = useState(false)
  const [busy, setBusy] = useState(false)
  const once = useSingleFlight()
  const customer = item.customerName ?? 'Cliente'
  // Foco (teclado e leitor de tela): a confirmação abre em "Voltar" (a opção segura); ao fechar volta para "Cancelar agendamento";
  // quando o botão pressionado some (a situação mudou), o foco vai para a própria linha.
  const rowRef = useRef<HTMLLIElement>(null)
  const openRef = useRef<HTMLButtonElement>(null)
  const backRef = useRef<HTMLButtonElement>(null)
  const wasConfirming = useRef(false)
  useEffect(() => {
    if (confirming) backRef.current?.focus()
    else if (wasConfirming.current) openRef.current?.focus()
    wasConfirming.current = confirming
  }, [confirming])

  /** Roda uma ação da equipe: bloqueia os botões durante o envio, avisa e, se o servidor já mudou, refaz a lista. */
  async function act(run: () => Promise<void>, removesRow = false) {
    await once(async () => {
      setProblem(null)
      setNotice(null)
      setBusy(true)
      try {
        await run()
        setConfirming(false)
        wasConfirming.current = false
        if (!removesRow) rowRef.current?.focus()
        await onChanged()
      } catch (e) {
        setProblem(describeError(e))
        setConfirming(false)
        if (e instanceof ApiError && e.reason && STATE_CHANGED.has(e.reason)) {
          onStatus(null)
          await onChanged().catch(() => undefined)
        }
      } finally {
        setBusy(false)
      }
    })
  }

  const cancel = () =>
    act(async () => {
      await api.cancel(item.id)
      onCancelled() // some da lista agora; o GET seguinte só confirma
      setNotice(`Agendamento de ${customer} cancelado.`)
    }, true)
  const checkIn = () =>
    act(async () => {
      await api.checkIn(item.id)
      onStatus('CHECKED_IN')
      setNotice(`Chegada de ${customer} registrada.`)
    })
  const complete = () =>
    act(async () => {
      await api.complete(item.id)
      onStatus('COMPLETED')
      setNotice(`Atendimento de ${customer} concluído.`)
    })

  return (
    <li className="row appt" data-status={status} ref={rowRef} tabIndex={-1}>
      <span className="appt-info">
        <strong className="appt-time">
          {timeIn(item.startsAt, timeZone)}–{timeIn(item.endsAt, timeZone)}
        </strong>
        <span className="appt-who">{customer}</span>
        <span className="appt-service">{item.serviceName ?? 'Serviço'}</span>
        <span className="badge" data-status={status}>{STATUS_LABEL[status] ?? status}</span>
      </span>
      {status === 'SCHEDULED' && (
        <span className="actions">
          {!confirming ? (
            <>
              {canCheckIn && (
                <button type="button" className="button" disabled={busy} onClick={checkIn}>
                  {busy ? 'Enviando…' : 'Chegou'}
                </button>
              )}
              <button type="button" className="button secondary" ref={openRef} disabled={busy} onClick={() => setConfirming(true)}>
                Cancelar agendamento
              </button>
            </>
          ) : (
            <>
              <span>Cancelar o agendamento de {customer}?</span>
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
      {status === 'CHECKED_IN' && (
        <span className="actions">
          <button type="button" className="button" disabled={busy} onClick={complete}>
            {busy ? 'Enviando…' : 'Concluir atendimento'}
          </button>
        </span>
      )}
    </li>
  )
}
