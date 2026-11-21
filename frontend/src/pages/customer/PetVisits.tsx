import { useCallback, useMemo } from 'react'
import { Link } from 'react-router-dom'
import { longDate, todayIn } from '../../api/dates'
import { describeError } from '../../api/messages'
import { formatMoney } from '../../api/money'
import { petsApi, type PetVisit } from '../../api/pets'
import { useAuth } from '../../auth/session'
import { Loading } from '../Loading'
import { RefreshWarning } from '../RefreshWarning'
import { useLoad } from '../useLoad'

const STATUS_TEXT: Record<string, string> = {
  SCHEDULED: 'agendado',
  CHECKED_IN: 'chegou',
  IN_PROGRESS: 'em atendimento',
  READY: 'pronto para retirada',
  COMPLETED: 'concluído',
  CANCELLED: 'cancelado',
  NO_SHOW: 'faltou',
}

/**
 * Histórico de UM pet para o tutor (ADR 0019, fatia 6): as visitas da mais recente para a mais antiga, com serviço, estúdio e valor. Quem já foi
 * atendido ganha "Agendar de novo", que abre o Agendar com o mesmo estúdio, serviço e pet já escolhidos (o horário é sempre novo).
 */
export function PetVisits({ petId, petName, active }: { petId: string; petName: string; active: boolean }) {
  const { request } = useAuth()
  const api = useMemo(() => petsApi(request), [request])
  const load = useCallback(() => api.history(petId), [api, petId])
  const { data, error, loading, reload } = useLoad<PetVisit[]>(load)

  if (loading && !data) return <Loading>Carregando histórico…</Loading>
  if (error && !data) return <p role="alert" className="error">{describeError(error)}</p>
  const last = data?.find((v) => v.status === 'COMPLETED')
  return (
    <div role="group" aria-label={`Histórico de ${petName}`}>
      {error !== undefined && data && <RefreshWarning onRetry={reload} />}
      {data && data.length === 0 && <p className="hint">{petName} ainda não tem atendimentos.</p>}
      <ol className="history">
        {data?.map((v) => (
          <li key={v.id}>
            <span className="hint">{longDate(todayIn(v.timeZone ?? 'America/Sao_Paulo', new Date(v.startsAt)))}</span> · {v.serviceName ?? 'Serviço'}
            {v.businessName && <> · {v.businessName}</>}
            {v.priceCents != null && <> · {formatMoney(v.priceCents)}</>} · {STATUS_TEXT[v.status] ?? v.status}
          </li>
        ))}
      </ol>
      {last && active && (
        <p>
          <Link className="button secondary" to={`/agendar?estudio=${encodeURIComponent(last.providerId)}&servico=${encodeURIComponent(last.serviceId)}&pet=${encodeURIComponent(petId)}`}>
            Agendar de novo: {last.serviceName ?? 'mesmo serviço'}
          </Link>
        </p>
      )}
    </div>
  )
}
