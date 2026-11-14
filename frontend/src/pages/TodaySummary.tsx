import { useCallback, useEffect, useMemo, useState } from 'react'
import { Link } from 'react-router-dom'
import { agendaApi, type AgendaItem, type QueueItem } from '../api/agenda'
import { longDate, timeIn, todayIn } from '../api/dates'
import { summarize } from '../api/summary'
import { isNotConfigured } from '../api/errors'
import { ApiError } from '../api/client'
import { useAuth } from '../auth/session'
import { Loading } from './Loading'
import { useLoad } from './useLoad'

interface Today {
  businessName: string
  timeZone: string
  date: string
  items: AgendaItem[]
  queue: QueueItem[]
}

/** Resumo do dia do prestador e do atendente. Falhar aqui nunca esconde os atalhos da tela inicial: só avisa e oferece atualizar. */
export function TodaySummary() {
  const { request } = useAuth()
  const api = useMemo(() => agendaApi(request), [request])
  const load = useCallback(async (): Promise<Today> => {
    const workplace = await api.workplace()
    const date = todayIn(workplace.timeZone)
    const [items, queue] = await Promise.all([api.day(date), api.queue()])
    return { ...workplace, date, items, queue }
  }, [api])
  const { data, error, loading, reload } = useLoad<Today>(load)
  // O relógio anda com a tela aberta: a cada minuto (e ao voltar à aba) o "próximo" é recalculado e, se o dia do NEGÓCIO virou, o resumo é refeito.
  const [now, setNow] = useState(() => new Date())
  useEffect(() => {
    const tick = () => setNow(new Date())
    const onVisible = () => document.visibilityState === 'visible' && tick()
    const id = setInterval(tick, 60_000)
    document.addEventListener('visibilitychange', onVisible)
    return () => {
      clearInterval(id)
      document.removeEventListener('visibilitychange', onVisible)
    }
  }, [])
  const staleDay = data !== undefined && todayIn(data.timeZone, now) !== data.date
  useEffect(() => {
    // oxlint-disable-next-line react/set-state-in-effect -- refazer a leitura quando o dia do negócio vira é o uso legítimo do efeito
    if (staleDay) void reload()
  }, [staleDay, reload, now])

  if (loading && !data) return <Loading>Carregando o resumo de hoje…</Loading>
  // Sem negócio configurado ou atendente sem vínculo: as telas de Agenda e Fila já explicam; o resumo não repete.
  if (!data && (isNotConfigured(error) || (error instanceof ApiError && error.status === 403))) return null
  if (!data) {
    return (
      <p className="hint">
        Não foi possível carregar o resumo de hoje.{' '}
        <button type="button" className="button secondary" onClick={() => void reload()}>
          Atualizar resumo
        </button>
      </p>
    )
  }
  const { scheduled, checkedIn, completed, next } = summarize(data.items, now)
  return (
    <section className="today" aria-labelledby="today-title">
      <h2 id="today-title">Hoje, {longDate(data.date)}</h2>
      <p className="hint">{data.businessName}</p>
      <dl className="stats">
        <div data-status="SCHEDULED">
          <dt>Agendados</dt>
          <dd>{scheduled}</dd>
        </div>
        <div data-status="CHECKED_IN">
          <dt>Na fila</dt>
          <dd>{data.queue.length}</dd>
        </div>
        <div data-status="COMPLETED">
          <dt>Concluídos</dt>
          <dd>{completed}</dd>
        </div>
      </dl>
      <p className="next">
        {next ? (
          <>
            <strong>Próximo:</strong> {timeIn(next.startsAt, data.timeZone)} · {next.customerName ?? 'Cliente'} · {next.serviceName ?? 'Serviço'}
            {next.status === 'CHECKED_IN' && ' (já chegou)'}
          </>
        ) : (
          'Nenhum atendimento por vir hoje.'
        )}{' '}
        <Link to="/agenda">Abrir a agenda</Link>
        {checkedIn > 0 && (
          <>
            {' '}
            · <Link to="/fila">Ver a fila</Link>
          </>
        )}
      </p>
    </section>
  )
}
