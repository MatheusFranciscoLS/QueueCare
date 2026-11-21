import { useCallback, useMemo, useState, type FormEvent } from 'react'
import { Link } from 'react-router-dom'
import { isDate } from '../../api/dates'
import { describeError } from '../../api/messages'
import { ApiError } from '../../api/client'
import { providerApi, type TimeOff, type TimeOffCreated } from '../../api/provider'
import { useAuth } from '../../auth/session'
import { Field } from '../Field'
import { Loading } from '../Loading'
import { RefreshWarning } from '../RefreshWarning'
import { useLoad } from '../useLoad'
import { useSingleFlight } from '../useSingleFlight'

/** "25/12/2026" a partir de "2026-12-25" (só calendário, sem fuso). */
export function dayLabel(date: string): string {
  const [y, m, d] = date.split('-')
  return `${d}/${m}/${y}`
}

/** "25/12/2026" para um dia só; "20/12/2026 a 05/01/2027" para um período. */
export function periodLabel(period: Pick<TimeOff, 'startsOn' | 'endsOn'>): string {
  return period.startsOn === period.endsOn ? dayLabel(period.startsOn) : `${dayLabel(period.startsOn)} a ${dayLabel(period.endsOn)}`
}

/**
 * Folgas, feriados e férias (ADR 0024): o estúdio fecha por dias inteiros sem mexer no expediente da semana. Fechar NÃO cancela nada sozinho: se já havia
 * agendamentos no período, a tela diz quantos e leva à Agenda do primeiro dia, para a equipe remarcar ou cancelar um a um.
 */
export function TimeOffSection() {
  const { request } = useAuth()
  const api = useMemo(() => providerApi(request), [request])
  const load = useCallback(() => api.listTimeOff(), [api])
  const { data, error, loading, reload } = useLoad<TimeOff[]>(load)
  const [from, setFrom] = useState('')
  const [to, setTo] = useState('')
  const [reason, setReason] = useState('')
  const [errors, setErrors] = useState<{ from?: string; to?: string; reason?: string; form?: string }>({})
  const [created, setCreated] = useState<TimeOffCreated | null>(null)
  const [removed, setRemoved] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const once = useSingleFlight()

  async function submit(event: FormEvent) {
    event.preventDefault()
    await once(async () => {
      const found: typeof errors = {}
      if (!isDate(from)) found.from = 'Informe o primeiro dia fechado.'
      if (to && !isDate(to)) found.to = 'Informe uma data válida.'
      if (isDate(from) && isDate(to) && to < from) found.to = 'O último dia não pode ser antes do primeiro.'
      if (found.from || found.to) {
        setErrors(found)
        return
      }
      setErrors({})
      setCreated(null)
      setRemoved(null)
      setBusy(true)
      try {
        const result = await api.createTimeOff({ startsOn: from, endsOn: to || from, reason: reason.trim() || undefined })
        setCreated(result)
        setFrom('')
        setTo('')
        setReason('')
        await reload()
      } catch (e) {
        if (e instanceof ApiError && e.status === 400 && (e.fieldErrors.dates || e.fieldErrors.reason)) setErrors({ to: e.fieldErrors.dates, reason: e.fieldErrors.reason })
        else setErrors({ form: describeError(e) })
      } finally {
        setBusy(false)
      }
    })
  }

  async function remove(period: TimeOff) {
    await once(async () => {
      setErrors({})
      setCreated(null)
      setBusy(true)
      try {
        await api.deleteTimeOff(period.id)
        setRemoved(periodLabel(period))
        await reload()
      } catch (e) {
        setErrors({ form: describeError(e) })
      } finally {
        setBusy(false)
      }
    })
  }

  return (
    <section className="card time-off" aria-label="Folgas e feriados">
      <h2>Folgas e feriados</h2>
      <p className="hint">
        Feche dias inteiros (feriado, folga, férias) sem mexer no expediente da semana. Nesses dias ninguém consegue agendar, e a Agenda avisa que está fechado.
      </p>

      {loading && !data && <Loading />}
      {error !== undefined && data && <RefreshWarning onRetry={reload} />}
      {error !== undefined && !data && (
        <p role="alert" className="error">
          {describeError(error)}
        </p>
      )}
      {data && data.length === 0 && <p className="hint">Nenhuma folga marcada.</p>}
      {data && data.length > 0 && (
        <ul className="rows time-off__list">
          {data.map((period) => (
            <li key={period.id} className="row">
              <span>
                <strong>{periodLabel(period)}</strong>
                {period.reason ? <> · {period.reason}</> : null}
              </span>
              <button type="button" className="button secondary" disabled={busy} aria-label={`Remover a folga de ${periodLabel(period)}`} onClick={() => remove(period)}>
                Remover
              </button>
            </li>
          ))}
        </ul>
      )}

      <form onSubmit={submit} noValidate>
        <div className="field-row">
          <Field label="Primeiro dia" name="time-off-from" type="date" disabled={busy} value={from} error={errors.from} onChange={(e) => { setFrom(e.target.value); setErrors((x) => ({ ...x, from: undefined })) }} />
          <Field
            label="Último dia"
            name="time-off-to"
            type="date"
            disabled={busy}
            hint="Deixe vazio para fechar só um dia."
            value={to}
            error={errors.to}
            onChange={(e) => { setTo(e.target.value); setErrors((x) => ({ ...x, to: undefined })) }}
          />
        </div>
        <Field label="Motivo (opcional)" name="time-off-reason" disabled={busy} maxLength={80} placeholder="Natal, férias, curso…" value={reason} error={errors.reason} onChange={(e) => { setReason(e.target.value); setErrors((x) => ({ ...x, reason: undefined })) }} />
        <p role="alert" className="error" hidden={!errors.form}>
          {errors.form}
        </p>
        <button type="submit" className="button" disabled={busy}>
          {busy ? 'Salvando…' : 'Fechar esses dias'}
        </button>
      </form>

      <p role="status" className="notice" hidden={!created && !removed}>
        {created && (
          <>
            {periodLabel(created)} fechado.
            {created.affectedAppointments > 0 && (
              <>
                {' '}
                Já {created.affectedAppointments === 1 ? 'havia 1 agendamento' : `havia ${created.affectedAppointments} agendamentos`} nesse período: eles continuam marcados.{' '}
                <Link to={`/agenda?data=${created.startsOn}`}>Ver na Agenda</Link> para remarcar ou cancelar.
              </>
            )}
          </>
        )}
        {removed && <>Folga de {removed} removida: o dia volta a receber agendamentos.</>}
      </p>
    </section>
  )
}
