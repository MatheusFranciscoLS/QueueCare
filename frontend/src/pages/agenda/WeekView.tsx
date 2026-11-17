import { useCallback, useMemo } from 'react'
import { agendaApi, type AgendaItem } from '../../api/agenda'
import { addDays, shortWeekday, todayIn, weekTitle, longDate, timeIn } from '../../api/dates'
import { describeError } from '../../api/messages'
import { petDescription } from '../../api/pets'
import { useAuth } from '../../auth/session'
import { StateDot } from '../../ui/components'
import { STATE_LABEL, type AppointmentState } from '../../ui/states'
import { Loading } from '../Loading'
import { RefreshWarning } from '../RefreshWarning'
import { useLoad } from '../useLoad'

const STATE_BY_STATUS: Record<string, AppointmentState> = { SCHEDULED: 'scheduled', CHECKED_IN: 'waiting', COMPLETED: 'done' }

/**
 * A semana da agenda (ADR 0018 v2): leitura geral dos 7 dias a partir de `start` (uma segunda-feira, data local do negócio).
 * A regra pela LARGURA DA ÁREA DE CONTEÚDO (consulta de contêiner no CSS): sete colunas, um fio por coluna, a partir de 880 px; abaixo disso,
 * uma seção por dia, empilhada, e o fio continua. Cada dia é uma seção com título, e o dia abre a agenda dele.
 */
export function WeekView({ start, timeZone, onOpenDay }: { start: string; timeZone: string; onOpenDay: (date: string) => void }) {
  const { request } = useAuth()
  const api = useMemo(() => agendaApi(request), [request])
  const load = useCallback(() => api.week(start), [api, start])
  const { data, error, loading, reload } = useLoad<AgendaItem[]>(load)

  if (loading && !data) return <Loading />
  if (error && !data) {
    return (
      <p role="alert" className="error">
        {describeError(error)}
      </p>
    )
  }
  const days = Array.from({ length: 7 }, (_, i) => addDays(start, i))
  const byDay = new Map<string, AgendaItem[]>(days.map((d) => [d, []]))
  // O dia de cada atendimento é o do NEGÓCIO (fuso IANA), não o do aparelho: 02:00Z ainda é o dia anterior em São Paulo.
  for (const item of data ?? []) byDay.get(todayIn(timeZone, new Date(item.startsAt)))?.push(item)
  const today = todayIn(timeZone)
  const total = data?.length ?? 0
  return (
    <section className="week" aria-label={`Semana de ${weekTitle(start)}`}>
      {error !== undefined && data && <RefreshWarning onRetry={reload} />}
      {total === 0 && <p className="notice">Nenhum agendamento nesta semana.</p>}
      <div className="week-cols">
        {days.map((date) => {
          const items = byDay.get(date) ?? []
          return (
            <section key={date} className="week-day" aria-labelledby={`dia-${date}`} data-today={date === today ? 'true' : undefined}>
              <h3 id={`dia-${date}`} className="week-day-title">
                <button type="button" className="week-day-open" onClick={() => onOpenDay(date)}>
                  {shortWeekday(date)} <span className="week-day-number">{date.slice(8)}</span>
                  <span className="pp-sr"> (abrir o dia {longDate(date)})</span>
                </button>
                {date === today && <span className="week-today">hoje</span>}
              </h3>
              {items.length === 0 ? (
                <p className="week-empty">Sem agendamentos</p>
              ) : (
                <ul className="week-slots">
                  {items.map((item) => {
                    const state = STATE_BY_STATUS[item.status] ?? 'scheduled'
                    return (
                      <li key={item.id} className="week-slot" data-state={state}>
                        <StateDot state={state} />
                        <span className="week-time">{timeIn(item.startsAt, timeZone)}</span>
                        <span className="week-who">{item.pet ? item.pet.name : (item.customerName ?? 'Cliente')}</span>
                        {item.pet && <span className="week-meta">{petDescription(item.pet)}</span>}
                        <span className="week-state">{STATE_LABEL[state]}</span>
                      </li>
                    )
                  })}
                </ul>
              )}
            </section>
          )
        })}
      </div>
    </section>
  )
}
