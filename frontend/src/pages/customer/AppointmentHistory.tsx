import { useCallback, useMemo } from 'react'
import { longDate, timeIn, todayIn } from '../../api/dates'
import { describeError } from '../../api/messages'
import { bookingApi, type HistoryEntry } from '../../api/booking'
import { useAuth } from '../../auth/session'
import { Loading } from '../Loading'
import { RefreshWarning } from '../RefreshWarning'
import { useLoad } from '../useLoad'

const BY: Record<HistoryEntry['by'], string> = { CUSTOMER: 'por você', STAFF: 'pelo estúdio', SYSTEM: 'pelo sistema' }

function when(iso: string, zone: string): string {
  return `${longDate(todayIn(zone, new Date(iso)))}, ${timeIn(iso, zone)}`
}

/** Uma linha do histórico em português. O cliente nunca vê o nome de um funcionário: só "por você" ou "pelo estúdio". */
function describeEntry(entry: HistoryEntry, zone: string, showNames: boolean): string {
  // A equipe vê o nome de quem agiu (a API só o envia a ela); o cliente nunca: mesmo que viesse, a tela não o mostraria.
  const by = showNames && entry.actorName ? `por ${entry.actorName}` : BY[entry.by]
  switch (entry.type) {
    case 'SCHEDULED':
      return `Agendado ${by}`
    case 'RESCHEDULED':
      return entry.previousStartsAt && entry.newStartsAt
        ? `Remarcado ${by}: de ${when(entry.previousStartsAt, zone)} para ${when(entry.newStartsAt, zone)}`
        : `Remarcado ${by}`
    case 'CHECKED_IN':
      return 'Chegada registrada'
    case 'COMPLETED':
      return 'Atendimento concluído'
    case 'CANCELLED':
      return `Cancelado ${by}`
  }
}

/** Histórico do agendamento (somente leitura), em ordem; carrega ao abrir. */
export function AppointmentHistory({ appointmentId, timeZone, showNames = false }: { appointmentId: string; timeZone: string; showNames?: boolean }) {
  const { request } = useAuth()
  const api = useMemo(() => bookingApi(request), [request])
  const load = useCallback(() => api.history(appointmentId), [api, appointmentId])
  const { data, error, loading, reload } = useLoad<HistoryEntry[]>(load)

  if (loading && !data) return <Loading>Carregando histórico…</Loading>
  if (error && !data) return <p role="alert" className="error">{describeError(error)}</p>
  return (
    <div aria-label="Histórico do agendamento" role="group">
      {error !== undefined && data && <RefreshWarning onRetry={reload} />}
      <ol className="history">
        {data?.map((entry, index) => (
          <li key={`${entry.type}-${entry.at}-${index}`}>
            <span className="hint">{when(entry.at, timeZone)}</span> · {describeEntry(entry, timeZone, showNames)}
          </li>
        ))}
      </ol>
    </div>
  )
}
