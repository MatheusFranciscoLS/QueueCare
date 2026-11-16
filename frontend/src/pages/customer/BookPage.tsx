import { useCallback, useMemo, useState } from 'react'
import { Link } from 'react-router-dom'
import { addDays, isDate, longDate, todayIn } from '../../api/dates'
import { describeError } from '../../api/messages'
import { ApiError } from '../../api/client'
import { bookingApi, type BookedAppointment, type CatalogProvider, type CatalogService, type FreeSlot } from '../../api/booking'
import { petDescription, petsApi, type Pet, type PetInput } from '../../api/pets'
import { useAuth } from '../../auth/session'
import { RefreshWarning } from '../RefreshWarning'
import { useLoad } from '../useLoad'
import { useSingleFlight } from '../useSingleFlight'
import { Loading } from '../Loading'
import { PetForm } from './PetForm'

/** Quantos dias à frente a API oferece horários (SlotService.MAX_DAYS_AHEAD). */
const MAX_DAYS_AHEAD = 90
const PAGE_SIZE = 50
/** Recusas da API que dizem respeito ao PET (não ao horário): o horário escolhido continua valendo. */
const PET_REASONS = new Set(['PET_NOT_FOUND', 'PET_INACTIVE'])

/**
 * Agendar: prestador, serviço, dia, horário livre e confirmação. Cada passo só aparece depois do anterior; trocar uma escolha
 * descarta as seguintes. As regras (disponibilidade, antecedência, reserva dupla) são da API; um 409 ou 422 ao reservar mostra o
 * motivo e atualiza os horários. O pet é obrigatório: escolhe-se um dos ativos (ou cadastra-se um na hora); com um só pet ativo ele já vem
 * escolhido. Recusa por causa do PET (arquivado em outra tela, por exemplo) mantém o horário e refaz a lista de pets.
 */
export function BookPage() {
  const [provider, setProvider] = useState<CatalogProvider | null>(null)
  const [service, setService] = useState<CatalogService | null>(null)
  const [date, setDate] = useState<string | null>(null)
  const [slot, setSlot] = useState<FreeSlot | null>(null)
  // O que foi CONFIRMADO, copiado no instante do envio: o resumo mostra exatamente o que a API recebeu, mesmo que a tela mude depois.
  const [done, setDone] = useState<{ appointment: BookedAppointment; serviceName: string; businessName: string; localStart: string; petName: string } | null>(null)
  // Enquanto uma reserva está em andamento NENHUMA escolha pode mudar (prestador, serviço, dia, horário): o estado fica aqui, no pai.
  const [submitting, setSubmitting] = useState(false)
  const once = useSingleFlight()
  const { request } = useAuth()
  const api = useMemo(() => bookingApi(request), [request])
  const pets = useMemo(() => petsApi(request), [request])
  const loadPets = useCallback(() => pets.list(), [pets])
  const petList = useLoad<Pet[]>(loadPets)
  const [petChoice, setPetChoice] = useState<string | null>(null)
  // Um só pet ativo já vem escolhido; com mais de um a pessoa escolhe. Um id que sumiu da lista (arquivado em outra tela) deixa de valer.
  const pet = petList.data?.find((p) => p.id === petChoice) ?? (petList.data?.length === 1 ? petList.data[0] : null)
  // Sobe quando a API recusa o horário escolhido: a lista de horários é refeita do zero.
  const [slotsVersion, setSlotsVersion] = useState(0)
  const [bookingError, setBookingError] = useState<string | null>(null)

  async function confirm() {
    if (!provider || !service || !slot || !pet) return
    const sent = { provider, service, slot, pet } // snapshot imutável do que está sendo enviado
    await once(async () => {
      setSubmitting(true)
      try {
        const appointment = await api.book({ providerId: sent.provider.id, serviceId: sent.service.id, localStart: sent.slot.localStart, petId: sent.pet.id })
        setDone({ appointment, serviceName: sent.service.name, businessName: sent.provider.businessName, localStart: sent.slot.localStart, petName: sent.pet.name })
      } catch (e) {
        // A API recusou este horário: mostra o motivo e refaz a lista de horários (o escolhido pode não existir mais).
        setBookingError(describeError(e))
        if (e instanceof ApiError && e.reason && PET_REASONS.has(e.reason)) {
          // O problema é o pet: o horário continua valendo. Refaz a lista de pets (o pet pode ter sido arquivado) e pede nova escolha.
          setPetChoice(null)
          await petList.reload()
        } else {
          setSlot(null)
          setSlotsVersion((v) => v + 1)
        }
      } finally {
        setSubmitting(false)
      }
    })
  }

  if (done) {
    return (
      <section className="card">
        <h1>Agendamento confirmado</h1>
        <p role="status" className="notice">
          {done.serviceName} para {done.petName} em {done.businessName}, {longDate(done.localStart.slice(0, 10))}, às {done.localStart.slice(11, 16)} (horário do negócio).
        </p>
        <button
          type="button"
          className="button"
          onClick={() => {
            setProvider(null)
            setService(null)
            setDate(null)
            setSlot(null)
            setDone(null)
            setBookingError(null)
          }}
        >
          Agendar outro horário
        </button>
      </section>
    )
  }

  return (
    <section className="card">
      <h1>Agendar</h1>
      <p role="alert" className="error" hidden={!bookingError}>
        {bookingError}
      </p>
      <ProviderStep
        disabled={submitting}
        selected={provider}
        onSelect={(p) => {
          setProvider(p)
          setService(null)
          setDate(null)
          setSlot(null)
          setBookingError(null)
        }}
      />
      {provider && (
        <ServiceStep
          key={provider.id}
          disabled={submitting}
          provider={provider}
          selected={service}
          onSelect={(s) => {
            setService(s)
            setDate(todayIn(provider.timeZone))
            setSlot(null)
            setBookingError(null)
          }}
        />
      )}
      {provider && service && date && (
        <>
          <DayStep
            disabled={submitting}
            provider={provider}
            date={date}
            onChange={(d) => {
              setDate(d)
              setSlot(null)
              setBookingError(null)
            }}
          />
          <SlotStep
            key={`${provider.id}|${service.id}|${date}|${slotsVersion}`}
            provider={provider}
            service={service}
            date={date}
            disabled={submitting}
            selected={slot}
            onSelect={(s) => {
              setSlot(s)
              setBookingError(null)
            }}
          />
        </>
      )}
      {provider && service && slot && (
        <>
          <PetStep
            pets={petList.data}
            loading={petList.loading}
            error={petList.error}
            reload={petList.reload}
            selected={pet}
            disabled={submitting}
            onSelect={(id) => {
              setPetChoice(id)
              setBookingError(null)
            }}
            onCreate={async (input) => {
              const created = await pets.create(input)
              await petList.reload()
              setPetChoice(created.id)
              setBookingError(null)
            }}
          />
          <ConfirmStep provider={provider} service={service} slot={slot} pet={pet} busy={submitting} onConfirm={confirm} onCancel={() => setSlot(null)} />
        </>
      )}
    </section>
  )
}

function ProviderStep({ selected, disabled, onSelect }: { selected: CatalogProvider | null; disabled: boolean; onSelect: (p: CatalogProvider) => void }) {
  const { request } = useAuth()
  const api = useMemo(() => bookingApi(request), [request])
  const load = useCallback(() => api.providers(0, PAGE_SIZE), [api])
  const { data, error, loading, reload } = useLoad<CatalogProvider[]>(load)

  if (loading && !data) return <Loading>Carregando prestadores…</Loading>
  if (error && !data) return <p role="alert" className="error">{describeError(error)}</p>
  return (
    <fieldset className="step">
      <legend>1. Escolha o prestador</legend>
      {error !== undefined && data && <RefreshWarning onRetry={reload} />}
      {data && data.length === 0 && <p className="notice">Ainda não há prestadores disponíveis.</p>}
      {data?.map((p) => (
        <label key={p.id} className="choice">
          <input type="radio" name="provider" disabled={disabled} checked={selected?.id === p.id} onChange={() => onSelect(p)} />
          <span>{p.businessName}</span>
        </label>
      ))}
      {data && data.length === PAGE_SIZE && <p className="hint">Mostrando os {PAGE_SIZE} primeiros prestadores; a busca por nome chega em breve.</p>}
    </fieldset>
  )
}

function ServiceStep({ provider, selected, disabled, onSelect }: { provider: CatalogProvider; selected: CatalogService | null; disabled: boolean; onSelect: (s: CatalogService) => void }) {
  const { request } = useAuth()
  const api = useMemo(() => bookingApi(request), [request])
  const load = useCallback(() => api.services(provider.id), [api, provider.id])
  const { data, error, loading, reload } = useLoad<CatalogService[]>(load)

  if (loading && !data) return <Loading>Carregando serviços…</Loading>
  if (error && !data) return <p role="alert" className="error">{describeError(error)}</p>
  return (
    <fieldset className="step">
      <legend>2. Escolha o serviço</legend>
      {error !== undefined && data && <RefreshWarning onRetry={reload} />}
      {data && data.length === 0 && <p className="notice">Este prestador ainda não tem serviços para agendar.</p>}
      {data?.map((s) => (
        <label key={s.id} className="choice">
          <input type="radio" name="service" disabled={disabled} checked={selected?.id === s.id} onChange={() => onSelect(s)} />
          <span>
            {s.name} · {s.durationMinutes} min
          </span>
        </label>
      ))}
    </fieldset>
  )
}

function DayStep({ provider, date, disabled, onChange }: { provider: CatalogProvider; date: string; disabled: boolean; onChange: (date: string) => void }) {
  // Os limites são do DIA DO NEGÓCIO (fuso do prestador), não do aparelho.
  const today = todayIn(provider.timeZone)
  return (
    <fieldset className="step">
      <legend>3. Escolha o dia</legend>
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

function SlotStep({
  provider,
  service,
  date,
  selected,
  disabled,
  onSelect,
}: {
  provider: CatalogProvider
  service: CatalogService
  date: string
  disabled: boolean
  selected: FreeSlot | null
  onSelect: (s: FreeSlot) => void
}) {
  const { request } = useAuth()
  const api = useMemo(() => bookingApi(request), [request])
  const load = useCallback(() => api.slots(provider.id, service.id, date), [api, provider.id, service.id, date])
  const { data, error, loading, reload } = useLoad<FreeSlot[]>(load)

  if (loading && !data) return <Loading>Carregando horários…</Loading>
  if (error && !data) return <p role="alert" className="error">{describeError(error)}</p>
  return (
    <fieldset className="step">
      <legend>4. Escolha o horário</legend>
      {error !== undefined && data && <RefreshWarning onRetry={reload} />}
      {data && data.length === 0 && <p className="notice">Nenhum horário livre neste dia. Tente outro dia.</p>}
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

function ConfirmStep({
  provider,
  service,
  slot,
  pet,
  busy,
  onConfirm,
  onCancel,
}: {
  provider: CatalogProvider
  service: CatalogService
  slot: FreeSlot
  pet: Pet | null
  busy: boolean
  onConfirm: () => void
  onCancel: () => void
}) {
  return (
    <section className="step confirm" aria-label="Confirmar agendamento">
      <h2>6. Confirme</h2>
      <p>
        <strong>{service.name}</strong> ({service.durationMinutes} min){pet && <> para <strong>{pet.name}</strong></>} em <strong>{provider.businessName}</strong>, {longDate(slot.localStart.slice(0, 10))}, às{' '}
        <strong>{slot.localStart.slice(11, 16)}</strong> (horário do negócio).
      </p>
      <span className="actions">
        <button type="button" className="button" disabled={busy || !pet} onClick={onConfirm}>
          {busy ? 'Agendando…' : 'Confirmar agendamento'}
        </button>
        <button type="button" className="button secondary" disabled={busy} onClick={onCancel}>
          Escolher outro horário
        </button>
      </span>
    </section>
  )
}

/** Passo do pet: escolher um dos ativos ou cadastrar um na hora. Sem pet escolhido o botão de confirmar fica desabilitado. */
function PetStep({
  pets,
  loading,
  error,
  reload,
  selected,
  disabled,
  onSelect,
  onCreate,
}: {
  pets: Pet[] | undefined
  loading: boolean
  error: unknown
  reload: () => Promise<void>
  selected: Pet | null
  disabled: boolean
  onSelect: (id: string) => void
  onCreate: (input: PetInput) => Promise<void>
}) {
  const [adding, setAdding] = useState(false)
  if (loading && !pets) return <Loading>Carregando seus pets…</Loading>
  if (error && !pets) {
    return (
      <p role="alert" className="error">
        {describeError(error)}{' '}
        <button type="button" className="button secondary" onClick={reload}>
          Tentar de novo
        </button>
      </p>
    )
  }
  const none = !pets || pets.length === 0
  return (
    <fieldset className="step">
      <legend>5. Escolha o pet</legend>
      {error !== undefined && pets && <RefreshWarning onRetry={reload} />}
      {none && <p className="notice">Cadastre o pet que será atendido.</p>}
      {pets?.map((p) => (
        <label key={p.id} className="choice">
          <input type="radio" name="pet" disabled={disabled} checked={selected?.id === p.id} onChange={() => onSelect(p.id)} />
          <span>
            {p.name} · {petDescription(p)}
          </span>
        </label>
      ))}
      {!none && !adding && (
        <p>
          <button type="button" className="button secondary" disabled={disabled} onClick={() => setAdding(true)}>
            Cadastrar outro pet
          </button>{' '}
          <Link to="/pets">Gerenciar meus pets</Link>
        </p>
      )}
      {(none || adding) && (
        <PetForm
          idPrefix="book-pet"
          submitLabel="Cadastrar e escolher"
          busyLabel="Cadastrando…"
          onCancel={none ? undefined : () => setAdding(false)}
          onSubmit={async (input) => {
            await onCreate(input)
            setAdding(false)
          }}
        />
      )}
    </fieldset>
  )
}
