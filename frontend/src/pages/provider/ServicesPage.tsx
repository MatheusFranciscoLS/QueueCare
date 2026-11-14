import { useCallback, useMemo, useState, type FormEvent } from 'react'
import { Link } from 'react-router-dom'
import { ApiError } from '../../api/client'
import { isNotConfigured } from '../../api/errors'
import { describeError } from '../../api/messages'
import { providerApi, type Service } from '../../api/provider'
import { useAuth } from '../../auth/session'
import { Field } from '../Field'
import { useLoad } from '../useLoad'
import { useSingleFlight } from '../useSingleFlight'

const MIN_MINUTES = 5
const MAX_MINUTES = 480

function validate(name: string, duration: string): { name?: string; duration?: string } {
  const minutes = Number(duration)
  return {
    name: name.trim() ? (name.trim().length > 120 ? 'No máximo 120 caracteres.' : undefined) : 'Informe o nome do serviço.',
    duration: Number.isInteger(minutes) && minutes >= MIN_MINUTES && minutes <= MAX_MINUTES ? undefined : `Informe uma duração entre ${MIN_MINUTES} e ${MAX_MINUTES} minutos.`,
  }
}

export function ServicesPage() {
  const { request } = useAuth()
  const api = useMemo(() => providerApi(request), [request])
  const load = useCallback(() => api.listServices(), [api])
  const { data, error, loading, reload } = useLoad<Service[]>(load)

  if (loading && !data) return <p role="status">Carregando…</p>
  if (isNotConfigured(error)) {
    return (
      <section className="card">
        <h1>Serviços</h1>
        <p className="notice">
          Cadastre primeiro o seu negócio em <Link to="/meu-negocio">Meu negócio</Link>.
        </p>
      </section>
    )
  }
  if (error && !data) return <p role="alert" className="error">{describeError(error)}</p>

  return (
    <section className="card">
      <h1>Serviços</h1>
      {data && data.length === 0 && <p className="notice">Você ainda não tem serviços. Cadastre o primeiro abaixo.</p>}
      <ul className="rows">
        {data?.map((service) => (
          <ServiceRow key={service.id} service={service} onChanged={reload} />
        ))}
      </ul>
      <h2>Novo serviço</h2>
      <NewService onCreated={reload} />
    </section>
  )
}

function NewService({ onCreated }: { onCreated: () => void }) {
  const { request } = useAuth()
  const api = useMemo(() => providerApi(request), [request])
  const [name, setName] = useState('')
  const [duration, setDuration] = useState('60')
  const [errors, setErrors] = useState<{ name?: string; duration?: string; form?: string }>({})
  const [busy, setBusy] = useState(false)
  const once = useSingleFlight()

  async function submit(event: FormEvent) {
    event.preventDefault()
    await once(async () => {
      const found = validate(name, duration)
      if (found.name || found.duration) {
        setErrors(found)
        return
      }
      setErrors({})
      setBusy(true)
      try {
        await api.createService({ name: name.trim(), durationMinutes: Number(duration) })
        setName('')
        setDuration('60')
        onCreated()
      } catch (e) {
        setErrors(e instanceof ApiError && e.status === 400 ? { name: e.fieldErrors.name, duration: e.fieldErrors.durationMinutes, form: describeError(e) } : { form: describeError(e) })
      } finally {
        setBusy(false)
      }
    })
  }

  return (
    <form onSubmit={submit} noValidate>
      <Field label="Nome do serviço" name="new-name" value={name} error={errors.name} onChange={(e) => { setName(e.target.value); setErrors((x) => ({ ...x, name: undefined })) }} />
      <Field
        label="Duração (minutos)"
        name="new-duration"
        type="number"
        inputMode="numeric"
        min={MIN_MINUTES}
        max={MAX_MINUTES}
        step={5}
        value={duration}
        error={errors.duration}
        onChange={(e) => { setDuration(e.target.value); setErrors((x) => ({ ...x, duration: undefined })) }}
      />
      <p role="alert" className="error" hidden={!errors.form}>
        {errors.form}
      </p>
      <button type="submit" className="button" disabled={busy}>
        {busy ? 'Salvando…' : 'Adicionar serviço'}
      </button>
    </form>
  )
}

function ServiceRow({ service, onChanged }: { service: Service; onChanged: () => void }) {
  const { request } = useAuth()
  const api = useMemo(() => providerApi(request), [request])
  const [editing, setEditing] = useState(false)
  const [name, setName] = useState(service.name)
  const [duration, setDuration] = useState(String(service.durationMinutes))
  const [errors, setErrors] = useState<{ name?: string; duration?: string; form?: string }>({})
  const [busy, setBusy] = useState(false)
  const once = useSingleFlight()

  async function save(active: boolean) {
    await once(async () => {
      const found = validate(name, duration)
      if (found.name || found.duration) {
        setErrors(found)
        return
      }
      setErrors({})
      setBusy(true)
      try {
        await api.updateService(service.id, { name: name.trim(), durationMinutes: Number(duration), active })
        setEditing(false)
        onChanged()
      } catch (e) {
        setErrors({ form: describeError(e) })
      } finally {
        setBusy(false)
      }
    })
  }

  if (!editing) {
    return (
      <li className="row">
        <span>
          <strong>{service.name}</strong> · {service.durationMinutes} min {!service.active && <span className="badge">Desativado</span>}
        </span>
        <span className="actions">
          <button type="button" className="button secondary" onClick={() => setEditing(true)}>
            Editar
          </button>
          <button type="button" className="button secondary" disabled={busy} onClick={() => save(!service.active)}>
            {service.active ? 'Desativar' : 'Reativar'}
          </button>
        </span>
        {errors.form && (
          <p role="alert" className="error">
            {errors.form}
          </p>
        )}
      </li>
    )
  }
  return (
    <li className="row editing">
      <Field label="Nome" name={`name-${service.id}`} value={name} error={errors.name} onChange={(e) => setName(e.target.value)} />
      <Field label="Duração (minutos)" name={`duration-${service.id}`} type="number" inputMode="numeric" min={MIN_MINUTES} max={MAX_MINUTES} step={5} value={duration} error={errors.duration} onChange={(e) => setDuration(e.target.value)} />
      <p role="alert" className="error" hidden={!errors.form}>
        {errors.form}
      </p>
      <span className="actions">
        <button type="button" className="button" disabled={busy} onClick={() => save(service.active)}>
          Salvar
        </button>
        <button
          type="button"
          className="button secondary"
          onClick={() => {
            setEditing(false)
            setName(service.name)
            setDuration(String(service.durationMinutes))
            setErrors({})
          }}
        >
          Cancelar
        </button>
      </span>
    </li>
  )
}
