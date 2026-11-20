import { useCallback, useEffect, useMemo, useState, type CSSProperties } from 'react'
import { agendaApi, type AgendaItem } from '../../api/agenda'
import { addDays, longDate, shortWeekday, timeIn, todayIn, weekTitle } from '../../api/dates'
import { describeError } from '../../api/messages'
import { petDescription } from '../../api/pets'
import { useAuth } from '../../auth/session'
import { PetAvatar, StateDot } from '../../ui/components'
import { STATE_LABEL, type AppointmentState } from '../../ui/states'
import { Loading } from '../Loading'
import { hourHeight } from './weekScale'
import { RefreshWarning } from '../RefreshWarning'
import { useLoad } from '../useLoad'

const STATE_BY_STATUS: Record<string, AppointmentState> = { SCHEDULED: 'scheduled', CHECKED_IN: 'waiting', COMPLETED: 'done' }

/** "HH:mm" em minutos desde a meia-noite. */
const minutesOf = (hhmm: string) => Number(hhmm.slice(0, 2)) * 60 + Number(hhmm.slice(3, 5))

interface Block {
  item: AgendaItem
  state: AppointmentState
  start: string
  end: string
  startMin: number
  duration: number
}

/**
 * A semana da agenda (ADR 0018 v2): uma GRADE DE HORÁRIOS. Cada atendimento é um bloco na hora dele, com a altura da duração, em uma coluna
 * por dia (a hora e o dia são os do NEGÓCIO, fuso IANA). A regra é pela LARGURA DA ÁREA DE CONTEÚDO (consulta de contêiner no CSS): a grade a
 * partir de 880 px; abaixo disso, uma seção por dia, empilhada, com os mesmos dados em lista. Um dia só tem um bloco por horário (a reserva
 * impede atendimentos sobrepostos do mesmo prestador), então não há blocos lado a lado.
 */
export function WeekView({ start, timeZone, onOpenDay }: { start: string; timeZone: string; onOpenDay: (date: string) => void }) {
  const { request } = useAuth()
  const api = useMemo(() => agendaApi(request), [request])
  const load = useCallback(() => api.week(start), [api, start])
  const { data, error, loading, reload } = useLoad<AgendaItem[]>(load)
  // O relógio anda com a tela aberta: a linha de "agora" acompanha, no fuso do negócio.
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

  if (loading && !data) return <Loading />
  if (error && !data) {
    return (
      <p role="alert" className="error">
        {describeError(error)}
      </p>
    )
  }
  const days = Array.from({ length: 7 }, (_, i) => addDays(start, i))
  const byDay = new Map<string, Block[]>(days.map((d) => [d, []]))
  // O dia e a hora de cada atendimento são os do NEGÓCIO, não os do aparelho: 02:00Z ainda é o dia anterior em São Paulo.
  for (const item of data ?? []) {
    const startHm = timeIn(item.startsAt, timeZone)
    const startMin = minutesOf(startHm)
    const duration = Math.max(15, Math.round((Date.parse(item.endsAt) - Date.parse(item.startsAt)) / 60_000))
    byDay.get(todayIn(timeZone, new Date(item.startsAt)))?.push({ item, state: STATE_BY_STATUS[item.status] ?? 'scheduled', start: startHm, end: timeIn(item.endsAt, timeZone), startMin, duration })
  }
  const blocks = [...byDay.values()].flat()
  // O eixo das horas cobre o expediente comum (8 às 18) e se estende se houver atendimento fora dele.
  const firstHour = Math.max(0, Math.min(8, ...blocks.map((b) => Math.floor(b.startMin / 60))))
  const lastHour = Math.min(24, Math.max(18, ...blocks.map((b) => Math.ceil((b.startMin + b.duration) / 60))))
  const rows = lastHour - firstHour
  const today = todayIn(timeZone)
  const nowMin = minutesOf(timeIn(now.toISOString(), timeZone))
  const hourPx = hourHeight(blocks.map((b) => b.duration))
  const grid = { '--rows': rows, '--first': firstHour * 60, '--h': `${hourPx}px` } as CSSProperties
  return (
    <section className="week" aria-label={`Semana de ${weekTitle(start)}`} style={grid}>
      {error !== undefined && data && <RefreshWarning onRetry={reload} />}
      {blocks.length === 0 && <p className="notice">Nenhum agendamento nesta semana.</p>}
      <div className="week-cols">
        <div className="week-hours" aria-hidden="true">
          <span className="week-hours__spacer" />
          <div className="week-hours__axis">
            {Array.from({ length: rows }, (_, i) => (
              <span key={i}>{String(firstHour + i).padStart(2, '0')}:00</span>
            ))}
          </div>
        </div>
        {days.map((date) => {
          const items = byDay.get(date) ?? []
          const isToday = date === today
          const showNow = isToday && nowMin >= firstHour * 60 && nowMin <= lastHour * 60
          return (
            <section key={date} className="week-day" aria-labelledby={`dia-${date}`} data-today={isToday ? 'true' : undefined}>
              <h3 id={`dia-${date}`} className="week-day-title">
                <button type="button" className="week-day-open" onClick={() => onOpenDay(date)}>
                  {shortWeekday(date)} <span className="week-day-number">{date.slice(8)}</span>
                  <span className="pp-sr"> (abrir o dia {longDate(date)})</span>
                </button>
                {isToday && <span className="week-today">hoje</span>}
                {isToday && <span className="week-now-tag">agora {timeIn(now.toISOString(), timeZone)}</span>}
              </h3>
              {items.length === 0 ? <p className="week-empty">Sem agendamentos</p> : null}
              <ul className="week-slots">
                {items.map(({ item, state, start: s, end, startMin, duration }) => (
                  <li key={item.id} className="week-block" data-state={state} data-short={duration <= 30 ? 'true' : undefined} style={{ '--start': startMin - firstHour * 60, '--dur': duration } as CSSProperties}>
                    <button type="button" className="week-block__open" onClick={() => onOpenDay(date)}>
                      {item.pet && <PetAvatar species={item.pet.species} size="sm" />}
                      <span className="week-block__time">
                        {s}
                        <span className="week-block__end">–{end}</span>
                      </span>
                      <span className="week-block__name">{item.pet ? item.pet.name : (item.customerName ?? 'Cliente')}</span>
                      <span className="week-block__service">{item.serviceName ?? 'Serviço'}</span>
                      {item.pet && <span className="week-block__meta">{petDescription(item.pet)}</span>}
                      <span className="week-block__state">
                        <StateDot state={state} />
                        <span className="week-block__state-text">{STATE_LABEL[state]}</span>
                      </span>
                    </button>
                  </li>
                ))}
                {showNow && <li className="week-now" aria-hidden="true" style={{ '--start': nowMin - firstHour * 60 } as CSSProperties} />}
              </ul>
            </section>
          )
        })}
      </div>
    </section>
  )
}
