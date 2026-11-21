import { useCallback, useMemo, type ReactNode } from 'react'
import { addDays, isDate, longDate, todayIn } from '../../api/dates'
import { describeError } from '../../api/messages'
import { bookingApi, type CatalogProvider, type CatalogService, type Closure, type FreeSlot } from '../../api/booking'
import { useAuth } from '../../auth/session'
import { RefreshWarning } from '../RefreshWarning'
import { useLoad } from '../useLoad'
import { Loading } from '../Loading'

/** Quantos dias à frente a API oferece horários (SlotService.MAX_DAYS_AHEAD). */
const MAX_DAYS_AHEAD = 90

/** Passos de dia e de horário, compartilhados por agendar e por remarcar. */
export function DayStep({
  provider,
  date,
  disabled,
  onChange,
  number = 3,
}: {
  provider: CatalogProvider
  date: string
  disabled: boolean
  onChange: (date: string) => void
  /** Número do passo na tela (em Agendar o pet vem antes, ADR 0020). */
  number?: number
}) {
  // Os limites são do DIA DO NEGÓCIO (fuso do prestador), não do aparelho.
  const today = todayIn(provider.timeZone)
  return (
    <fieldset className="step">
      <legend>{number}. Escolha o dia</legend>
      <div className="daybar">
        <button type="button" className="button secondary" disabled={disabled || date <= today} onClick={() => onChange(addDays(date, -1))}>
          Dia anterior
        </button>
        <button type="button" className="button secondary" disabled={disabled || date >= addDays(today, MAX_DAYS_AHEAD)} onClick={() => onChange(addDays(date, 1))}>
          Próximo dia
        </button>
        <label>
          Data
          <input
            type="date"
            disabled={disabled}
            value={date}
            min={today}
            max={addDays(today, MAX_DAYS_AHEAD)}
            onChange={(e) => {
              if (isDate(e.target.value) && e.target.value >= today && e.target.value <= addDays(today, MAX_DAYS_AHEAD)) onChange(e.target.value)
            }}
          />
        </label>
      </div>
      <p className="hint">{longDate(date)} · horários no fuso do negócio ({provider.timeZone})</p>
    </fieldset>
  )
}

export function SlotStep({
  provider,
  service,
  date,
  selected,
  disabled,
  onSelect,
  excludeAppointmentId,
  petId,
  number = 4,
  emptyExtra,
}: {
  provider: CatalogProvider
  service: CatalogService
  date: string
  disabled: boolean
  selected: FreeSlot | null
  onSelect: (s: FreeSlot) => void
  /** Remarcar: os horários ignoram o PRÓPRIO agendamento (a API só aceita se for do chamador). */
  excludeAppointmentId?: string
  /** Agendar: a duração depende do pet (ADR 0020); a API só considera um pet ativo do próprio cliente. */
  petId?: string
  number?: number
  /** O que mostrar junto do aviso de dia sem horários (Agendar: entrar na lista de espera, ADR 0022). */
  emptyExtra?: ReactNode
}) {
  const { request } = useAuth()
  const api = useMemo(() => bookingApi(request), [request])
  const load = useCallback(() => api.slots(provider.id, service.id, date, excludeAppointmentId, petId), [api, provider.id, service.id, date, excludeAppointmentId, petId])
  const { data, error, loading, reload } = useLoad<FreeSlot[]>(load)

  if (loading && !data) return <Loading>Carregando horários…</Loading>
  if (error && !data) return <p role="alert" className="error">{describeError(error)}</p>
  return (
    <fieldset className="step">
      <legend>{number}. Escolha o horário</legend>
      {error !== undefined && data && <RefreshWarning onRetry={reload} />}
      {data && data.length === 0 && <EmptyDay providerId={provider.id} date={date} emptyExtra={emptyExtra} />}
      <div className="slots" role="group" aria-label="Horários livres">
        {data?.map((s) => (
          <button
            key={s.localStart}
            type="button"
            className={selected?.localStart === s.localStart ? 'button' : 'button secondary'}
            aria-pressed={selected?.localStart === s.localStart}
            disabled={disabled}
            onClick={() => onSelect(s)}
          >
            {s.localStart.slice(11, 16)}
          </button>
        ))}
      </div>
      <p>
        <button type="button" className="button secondary" disabled={disabled} onClick={reload}>
          Atualizar horários
        </button>
      </p>
    </fieldset>
  )
}


/**
 * Dia sem horários livres. Se o estúdio FECHA nesse dia (folga, feriado ou férias, ADR 0024) diz isso, com o motivo, e não oferece a lista de espera
 * (ninguém vai vagar num dia fechado). Enquanto não se sabe (ou se a consulta falhar), vale o aviso genérico e a oferta continua.
 */
function EmptyDay({ providerId, date, emptyExtra }: { providerId: string; date: string; emptyExtra?: ReactNode }) {
  const { request, user } = useAuth()
  const api = useMemo(() => bookingApi(request), [request])
  // O catálogo é só do tutor: a equipe (que remarca por aqui) não consulta e vê o aviso genérico.
  const isCustomer = user?.role === 'CUSTOMER'
  const load = useCallback(() => (isCustomer ? api.closures(providerId) : Promise.resolve<Closure[]>([])), [api, providerId, isCustomer])
  const { data } = useLoad<Closure[]>(load)
  const closed = data?.find((c) => date >= c.startsOn && date <= c.endsOn)
  if (closed) {
    return (
      <p className="notice">
        O estúdio está fechado neste dia{closed.reason ? ` (${closed.reason})` : ''}. Escolha outro dia.
      </p>
    )
  }
  return (
    <>
      <p className="notice">Nenhum horário livre neste dia. Tente outro dia.</p>
      {emptyExtra}
    </>
  )
}
