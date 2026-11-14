import { useCallback, useMemo, useState } from 'react'
import { Link } from 'react-router-dom'
import { isNotConfigured } from '../../api/errors'
import { describeError } from '../../api/messages'
import { providerApi, type AvailabilityWindow } from '../../api/provider'
import { countWindows, DAYS, fromWindows, MAX_WINDOWS, toWindows, validateSchedule, type Schedule } from '../../api/schedule'
import { useAuth } from '../../auth/session'
import { useLoad } from '../useLoad'
import { useSingleFlight } from '../useSingleFlight'

export function AvailabilityPage() {
  const { request } = useAuth()
  const api = useMemo(() => providerApi(request), [request])
  const load = useCallback(() => api.getAvailability(), [api])
  const { data, error, loading, reload } = useLoad<AvailabilityWindow[]>(load)
  // O aviso "salvo" fica aqui (e não no editor) porque o editor é refeito quando o servidor devolve o conjunto gravado.
  const [saved, setSaved] = useState(false)

  if (loading && !data) return <p role="status">Carregando…</p>
  if (isNotConfigured(error)) {
    return (
      <section className="card">
        <h1>Expediente</h1>
        <p className="notice">
          Cadastre primeiro o seu negócio em <Link to="/meu-negocio">Meu negócio</Link>.
        </p>
      </section>
    )
  }
  if (error && !data) return <p role="alert" className="error">{describeError(error)}</p>
  // A chave muda quando o servidor devolve outro conjunto: a tela passa a refletir exatamente o que ficou gravado.
  return <Editor key={JSON.stringify(data ?? [])} windows={data ?? []} saved={saved} setSaved={setSaved} onSaved={reload} />
}

function Editor({ windows, saved, setSaved, onSaved }: { windows: AvailabilityWindow[]; saved: boolean; setSaved: (value: boolean) => void; onSaved: () => void }) {
  const { request } = useAuth()
  const api = useMemo(() => providerApi(request), [request])
  const [schedule, setSchedule] = useState<Schedule>(() => fromWindows(windows))
  const [errors, setErrors] = useState<ReturnType<typeof validateSchedule>>({ perDay: {} })
  const [formError, setFormError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const once = useSingleFlight()

  const change = (update: (current: Schedule) => Schedule) => {
    setSchedule(update)
    setSaved(false)
    setErrors({ perDay: {} })
  }

  async function save() {
    await once(async () => {
      const found = validateSchedule(schedule)
      if (Object.keys(found.perDay).length > 0 || found.total) {
        setErrors(found)
        return
      }
      setErrors({ perDay: {} })
      setFormError(null)
      setBusy(true)
      try {
        await api.replaceAvailability(toWindows(schedule))
        setSaved(true)
        onSaved()
      } catch (e) {
        setFormError(describeError(e))
      } finally {
        setBusy(false)
      }
    })
  }

  return (
    <section className="card">
      <h1>Expediente</h1>
      <p className="hint">Em que dias e horários você atende (no fuso do seu negócio). Dias sem horário ficam fechados.</p>
      {countWindows(schedule) === 0 && <p className="notice">Nenhum horário cadastrado: hoje o seu negócio não recebe agendamentos.</p>}
      {DAYS.map((day) => (
        <fieldset key={day.key} className="day">
          <legend>{day.label}</legend>
          {schedule[day.key].length === 0 && <p className="hint">Fechado</p>}
          {schedule[day.key].map((slot, index) => (
            <div key={index} className="slot">
              <label>
                Início
                <input
                  type="time"
                  aria-label={`${day.label}, horário ${index + 1}, início`}
                  value={slot.start}
                  onChange={(e) => change((s) => ({ ...s, [day.key]: s[day.key].map((x, i) => (i === index ? { ...x, start: e.target.value } : x)) }))}
                />
              </label>
              <label>
                Fim
                <input
                  type="time"
                  aria-label={`${day.label}, horário ${index + 1}, fim`}
                  value={slot.end}
                  onChange={(e) => change((s) => ({ ...s, [day.key]: s[day.key].map((x, i) => (i === index ? { ...x, end: e.target.value } : x)) }))}
                />
              </label>
              <button type="button" className="button secondary" aria-label={`Remover horário ${index + 1} de ${day.label}`} onClick={() => change((s) => ({ ...s, [day.key]: s[day.key].filter((_, i) => i !== index) }))}>
                Remover
              </button>
            </div>
          ))}
          <button
            type="button"
            className="button secondary"
            disabled={countWindows(schedule) >= MAX_WINDOWS}
            aria-label={`Adicionar horário em ${day.label}`}
            onClick={() => change((s) => ({ ...s, [day.key]: [...s[day.key], { start: '08:00', end: '12:00' }] }))}
          >
            Adicionar horário
          </button>
          {errors.perDay[day.key] && (
            <p role="alert" className="error">
              {errors.perDay[day.key]}
            </p>
          )}
        </fieldset>
      ))}
      {errors.total && (
        <p role="alert" className="error">
          {errors.total}
        </p>
      )}
      <p role="alert" className="error" hidden={!formError}>
        {formError}
      </p>
      <p role="status" className="notice" hidden={!saved}>
        Expediente salvo.
      </p>
      <button type="button" className="button" disabled={busy} onClick={save}>
        {busy ? 'Salvando…' : 'Salvar expediente'}
      </button>
    </section>
  )
}
