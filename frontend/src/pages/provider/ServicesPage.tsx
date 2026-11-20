import { useCallback, useMemo, useState, type FormEvent } from 'react'
import { Link } from 'react-router-dom'
import { ApiError } from '../../api/client'
import { isNotConfigured } from '../../api/errors'
import { describeError } from '../../api/messages'
import { formatMoney, moneyInput, parseMoney } from '../../api/money'
import { providerApi, type Service, type ServicePrices } from '../../api/provider'
import { useAuth } from '../../auth/session'
import { Field } from '../Field'
import { RefreshWarning } from '../RefreshWarning'
import { useLoad } from '../useLoad'
import { useSingleFlight } from '../useSingleFlight'
import { Loading } from '../Loading'

const MIN_MINUTES = 5
const MAX_MINUTES = 480

type PriceErrors = { price?: string; priceMedium?: string; priceLarge?: string }

const PRICE_ERROR = 'Informe o valor em reais, por exemplo 50 ou 49,90.'

/** Os três preços digitados (vazio = não informado) em centavos, ou o erro de cada campo. */
function readPrices(price: string, medium: string, large: string): { prices?: ServicePrices; errors: PriceErrors } {
  const values = [parseMoney(price), parseMoney(medium), parseMoney(large)]
  const errors: PriceErrors = {
    price: values[0] === undefined ? PRICE_ERROR : undefined,
    priceMedium: values[1] === undefined ? PRICE_ERROR : undefined,
    priceLarge: values[2] === undefined ? PRICE_ERROR : undefined,
  }
  if (errors.price || errors.priceMedium || errors.priceLarge) return { errors }
  return { prices: { priceCents: values[0] ?? null, priceMediumCents: values[1] ?? null, priceLargeCents: values[2] ?? null }, errors }
}

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

  if (loading && !data) return <Loading />
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
      {error !== undefined && data && <RefreshWarning onRetry={reload} />}
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

function NewService({ onCreated }: { onCreated: () => Promise<void> }) {
  const { request } = useAuth()
  const api = useMemo(() => providerApi(request), [request])
  const [name, setName] = useState('')
  const [duration, setDuration] = useState('60')
  const [price, setPrice] = useState('')
  const [priceMedium, setPriceMedium] = useState('')
  const [priceLarge, setPriceLarge] = useState('')
  const [errors, setErrors] = useState<{ name?: string; duration?: string; form?: string } & PriceErrors>({})
  const [busy, setBusy] = useState(false)
  const once = useSingleFlight()

  async function submit(event: FormEvent) {
    event.preventDefault()
    await once(async () => {
      const found = validate(name, duration)
      const read = readPrices(price, priceMedium, priceLarge)
      if (found.name || found.duration || !read.prices) {
        setErrors({ ...found, ...read.errors })
        return
      }
      setErrors({})
      setBusy(true)
      try {
        await api.createService({ name: name.trim(), durationMinutes: Number(duration), ...read.prices })
        setName('')
        setDuration('60')
        setPrice('')
        setPriceMedium('')
        setPriceLarge('')
        await onCreated()
      } catch (e) {
        // Erro de campo aparece no campo; o aviso geral só quando não há campo a apontar (sem repetir o mesmo texto).
        if (e instanceof ApiError && e.status === 400 && (e.fieldErrors.name || e.fieldErrors.durationMinutes || e.fieldErrors.priceCents || e.fieldErrors.priceMediumCents || e.fieldErrors.priceLargeCents))
          setErrors({ name: e.fieldErrors.name, duration: e.fieldErrors.durationMinutes, price: e.fieldErrors.priceCents, priceMedium: e.fieldErrors.priceMediumCents, priceLarge: e.fieldErrors.priceLargeCents })
        else setErrors({ form: describeError(e) })
      } finally {
        setBusy(false)
      }
    })
  }

  return (
    <form onSubmit={submit} noValidate>
      <div className="field-row">
      <Field label="Nome do serviço" name="new-name" disabled={busy} value={name} error={errors.name} onChange={(e) => { setName(e.target.value); setErrors((x) => ({ ...x, name: undefined })) }} />
      <Field
        label="Duração (minutos)"
        name="new-duration"
        disabled={busy}
        type="number"
        inputMode="numeric"
        min={MIN_MINUTES}
        max={MAX_MINUTES}
        step={5}
        value={duration}
        error={errors.duration}
        onChange={(e) => { setDuration(e.target.value); setErrors((x) => ({ ...x, duration: undefined })) }}
      />
      </div>
      <div className="field-row field-row--three">
      <Field label="Preço base (R$, opcional)" name="new-price" disabled={busy} inputMode="decimal" hint="Vazio = a combinar." value={price} error={errors.price} onChange={(e) => { setPrice(e.target.value); setErrors((x) => ({ ...x, price: undefined })) }} />
      <Field label="Preço para porte médio (R$, opcional)" name="new-price-medium" disabled={busy} inputMode="decimal" hint="Vazio = usa o preço base." value={priceMedium} error={errors.priceMedium} onChange={(e) => { setPriceMedium(e.target.value); setErrors((x) => ({ ...x, priceMedium: undefined })) }} />
      <Field label="Preço para porte grande (R$, opcional)" name="new-price-large" disabled={busy} inputMode="decimal" hint="Vazio = usa o preço base." value={priceLarge} error={errors.priceLarge} onChange={(e) => { setPriceLarge(e.target.value); setErrors((x) => ({ ...x, priceLarge: undefined })) }} />
      </div>
      <p role="alert" className="error" hidden={!errors.form}>
        {errors.form}
      </p>
      <button type="submit" className="button" disabled={busy}>
        {busy ? 'Salvando…' : 'Adicionar serviço'}
      </button>
    </form>
  )
}

/** Algum preço cadastrado? Sem nenhum, a linha não fala de preço (fica "a combinar" só no Agendar). */
function hasPrice(s: Service) {
  return s.priceCents != null || s.priceMediumCents != null || s.priceLargeCents != null
}

/** "R$ 50,00" ou, com preço por porte, "P R$ 50,00 · M R$ 70,00 · G R$ 90,00" (médio e grande sem valor próprio usam o base). */
function priceLine(s: Service): string {
  if (s.priceMediumCents == null && s.priceLargeCents == null) return formatMoney(s.priceCents ?? 0)
  const part = (label: string, cents: number | null | undefined) => (cents == null ? null : `${label} ${formatMoney(cents)}`)
  return [part('P', s.priceCents), part('M', s.priceMediumCents), part('G', s.priceLargeCents)].filter(Boolean).join(' · ')
}

function ServiceRow({ service, onChanged }: { service: Service; onChanged: () => Promise<void> }) {
  const { request } = useAuth()
  const api = useMemo(() => providerApi(request), [request])
  const [editing, setEditing] = useState(false)
  const [name, setName] = useState(service.name)
  const [duration, setDuration] = useState(String(service.durationMinutes))
  const [price, setPrice] = useState(moneyInput(service.priceCents))
  const [priceMedium, setPriceMedium] = useState(moneyInput(service.priceMediumCents))
  const [priceLarge, setPriceLarge] = useState(moneyInput(service.priceLargeCents))
  const [errors, setErrors] = useState<{ name?: string; duration?: string; form?: string } & PriceErrors>({})
  const [busy, setBusy] = useState(false)
  const once = useSingleFlight()

  async function save(active: boolean) {
    await once(async () => {
      const found = validate(name, duration)
      const read = readPrices(price, priceMedium, priceLarge)
      if (found.name || found.duration || !read.prices) {
        setErrors({ ...found, ...read.errors })
        return
      }
      setErrors({})
      setBusy(true)
      try {
        await api.updateService(service.id, { name: name.trim(), durationMinutes: Number(duration), active, ...read.prices })
        setEditing(false)
        await onChanged()
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
          <strong>{service.name}</strong> · {service.durationMinutes} min{hasPrice(service) && <> · {priceLine(service)}</>} {!service.active && <span className="badge">Desativado</span>}
        </span>
        <span className="actions">
          <button
            type="button"
            className="button secondary"
            disabled={busy}
            onClick={() => {
              // Ao abrir a edição, os campos partem dos valores ATUAIS do serviço (o que o servidor devolveu por último).
              setName(service.name)
              setDuration(String(service.durationMinutes))
              setPrice(moneyInput(service.priceCents))
              setPriceMedium(moneyInput(service.priceMediumCents))
              setPriceLarge(moneyInput(service.priceLargeCents))
              setErrors({})
              setEditing(true)
            }}
          >
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
      <div className="field-row">
      <Field label="Nome" name={`name-${service.id}`} disabled={busy} value={name} error={errors.name} onChange={(e) => setName(e.target.value)} />
      <Field label="Duração (minutos)" name={`duration-${service.id}`} disabled={busy} type="number" inputMode="numeric" min={MIN_MINUTES} max={MAX_MINUTES} step={5} value={duration} error={errors.duration} onChange={(e) => setDuration(e.target.value)} />
      </div>
      <div className="field-row field-row--three">
      <Field label="Preço base (R$, opcional)" name={`price-${service.id}`} disabled={busy} inputMode="decimal" hint="Vazio = a combinar." value={price} error={errors.price} onChange={(e) => { setPrice(e.target.value); setErrors((x) => ({ ...x, price: undefined })) }} />
      <Field label="Preço para porte médio (R$, opcional)" name={`price-medium-${service.id}`} disabled={busy} inputMode="decimal" hint="Vazio = usa o preço base." value={priceMedium} error={errors.priceMedium} onChange={(e) => { setPriceMedium(e.target.value); setErrors((x) => ({ ...x, priceMedium: undefined })) }} />
      <Field label="Preço para porte grande (R$, opcional)" name={`price-large-${service.id}`} disabled={busy} inputMode="decimal" hint="Vazio = usa o preço base." value={priceLarge} error={errors.priceLarge} onChange={(e) => { setPriceLarge(e.target.value); setErrors((x) => ({ ...x, priceLarge: undefined })) }} />
      </div>
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
          disabled={busy}
          onClick={() => {
            setEditing(false)
            setName(service.name)
            setDuration(String(service.durationMinutes))
            setPrice(moneyInput(service.priceCents))
            setPriceMedium(moneyInput(service.priceMediumCents))
            setPriceLarge(moneyInput(service.priceLargeCents))
            setErrors({})
          }}
        >
          Cancelar
        </button>
      </span>
    </li>
  )
}
