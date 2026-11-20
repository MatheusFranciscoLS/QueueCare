import { useCallback, useMemo } from 'react'
import { agendaApi, type PetVisitItem } from '../../api/agenda'
import { longDate, todayIn } from '../../api/dates'
import { describeError } from '../../api/messages'
import { formatMoney } from '../../api/money'
import { useAuth } from '../../auth/session'
import { Loading } from '../Loading'
import { RefreshWarning } from '../RefreshWarning'
import { useLoad } from '../useLoad'

/** "Quando foi o último banho?" (ADR 0019, fatia 6): as visitas anteriores concluídas do mesmo pet neste estúdio, da mais recente para a mais antiga. */
export function PetVisitsList({ appointmentId, petName, timeZone }: { appointmentId: string; petName: string; timeZone: string }) {
  const { request } = useAuth()
  const api = useMemo(() => agendaApi(request), [request])
  const load = useCallback(() => api.petVisits(appointmentId), [api, appointmentId])
  const { data, error, loading, reload } = useLoad<PetVisitItem[]>(load)

  if (loading && !data) return <Loading>Carregando visitas…</Loading>
  if (error && !data) return <p role="alert" className="error">{describeError(error)}</p>
  return (
    <div role="group" aria-label={`Visitas anteriores de ${petName}`}>
      {error !== undefined && data && <RefreshWarning onRetry={reload} />}
      {data && data.length === 0 && <p className="hint">{petName} não tem visitas anteriores aqui.</p>}
      <ol className="history">
        {data?.map((v, index) => (
          <li key={`${v.startsAt}-${index}`}>
            <span className="hint">{longDate(todayIn(timeZone, new Date(v.startsAt)))}</span> · {v.serviceName ?? 'Serviço'}
            {v.priceCents != null && <> · {formatMoney(v.priceCents)}</>}
          </li>
        ))}
      </ol>
    </div>
  )
}
