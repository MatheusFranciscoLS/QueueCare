import { useCallback, useEffect, useMemo, useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { longDate, todayIn } from '../../api/dates'
import { describeError } from '../../api/messages'
import { ApiError } from '../../api/client'
import { bookingApi, type BookedAppointment, type CatalogProvider, type CatalogService, type FreeSlot } from '../../api/booking'
import { durationForPet, durationSummary } from '../../api/duration'
import { formatMoney, priceForSize, priceSummary } from '../../api/money'
import { petDescription, petsApi, type Pet, type PetInput } from '../../api/pets'
import { useAuth } from '../../auth/session'
import { RefreshWarning } from '../RefreshWarning'
import { useLoad } from '../useLoad'
import { useSingleFlight } from '../useSingleFlight'
import { Loading } from '../Loading'
import { DayStep, SlotStep } from './BookingSteps'
import { PetForm } from './PetForm'

const PAGE_SIZE = 50
/** Recusas da API que dizem respeito ao PET (não ao horário): o horário escolhido continua valendo. */
const PET_REASONS = new Set(['PET_NOT_FOUND', 'PET_INACTIVE'])

/**
 * Agendar: prestador, serviço, pet, dia, horário livre e confirmação. O pet vem ANTES do dia porque a duração do atendimento depende do porte e do pelo dele
 * (ADR 0020), e com ela os horários livres. Cada passo só aparece depois do anterior; trocar uma escolha
 * descarta as seguintes. As regras (disponibilidade, antecedência, reserva dupla) são da API; um 409 ou 422 ao reservar mostra o
 * motivo e atualiza os horários. O pet é obrigatório: escolhe-se um dos ativos (ou cadastra-se um na hora); com um só pet ativo ele já vem
 * escolhido. Recusa por causa do PET (arquivado em outra tela, por exemplo) mantém o horário e refaz a lista de pets.
 */
export function BookPage() {
  // "Agendar de novo" (ADR 0019, fatia 6): o histórico do pet abre esta tela com estúdio, serviço e pet já escolhidos; só o horário é novo.
  // As escolhas vêm do endereço UMA vez: depois de usadas (ou de agendar), a pessoa escolhe livremente.
  const [params] = useSearchParams()
  const [wanted, setWanted] = useState<{ provider: string | null; service: string | null }>({ provider: params.get('estudio'), service: params.get('servico') })
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
  const [petChoice, setPetChoice] = useState<string | null>(params.get('pet'))
  // Versão da lista de pets no momento em que a API recusou um pet: enquanto nenhuma carga NOVA der certo, a lista é velha e não vale
  // (se a atualização falhar, os dados antigos continuam na tela, mas nenhum pet pode ser escolhido nem vir escolhido sozinho).
  const [refusedAtVersion, setRefusedAtVersion] = useState<number | null>(null)
  const petsFresh = refusedAtVersion === null || petList.version > refusedAtVersion
  // Um só pet ativo já vem escolhido; com mais de um a pessoa escolhe. Um id que sumiu da lista (arquivado em outra tela) deixa de valer.
  const pet = !petsFresh ? null : (petList.data?.find((p) => p.id === petChoice) ?? (petList.data?.length === 1 ? petList.data[0] : null))
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
        setWanted({ provider: null, service: null })
        setDone({ appointment, serviceName: sent.service.name, businessName: sent.provider.businessName, localStart: sent.slot.localStart, petName: sent.pet.name })
      } catch (e) {
        // A API recusou este horário: mostra o motivo e refaz a lista de horários (o escolhido pode não existir mais).
        setBookingError(describeError(e))
        if (e instanceof ApiError && e.reason && PET_REASONS.has(e.reason)) {
          // O problema é o pet: refaz a lista de pets (o pet pode ter sido arquivado) e pede nova escolha. Os horários eram os da duração DELE,
          // então o horário escolhido sai junto (a lista é refeita para o pet novo).
          setPetChoice(null)
          setSlot(null)
          setRefusedAtVersion(petList.version)
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
        preselectId={wanted.provider}
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
          preselectId={provider.id === wanted.provider ? wanted.service : null}
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
      {provider && service && (
        <PetStep
          pets={petList.data}
          loading={petList.loading}
          error={petList.error}
          reload={petList.reload}
          selected={pet}
          disabled={submitting || !petsFresh}
          onSelect={(id) => {
            setPetChoice(id)
            setSlot(null)
            setBookingError(null)
          }}
          onCreate={async (input) => {
            const created = await pets.create(input)
            await petList.reload()
            setPetChoice(created.id)
            setSlot(null)
            setBookingError(null)
          }}
        />
      )}
      {provider && service && pet && date && (
        <>
          <DayStep
            number={4}
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
            key={`${provider.id}|${service.id}|${pet.id}|${date}|${slotsVersion}`}
            number={5}
            petId={pet.id}
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
      {provider && service && pet && slot && (
        <ConfirmStep provider={provider} service={service} slot={slot} pet={pet} busy={submitting} onConfirm={confirm} onCancel={() => setSlot(null)} />
      )}
    </section>
  )
}

function ProviderStep({
  selected,
  disabled,
  preselectId,
  onSelect,
}: {
  selected: CatalogProvider | null
  disabled: boolean
  preselectId: string | null
  onSelect: (p: CatalogProvider) => void
}) {
  const { request } = useAuth()
  const api = useMemo(() => bookingApi(request), [request])
  const load = useCallback(() => api.providers(0, PAGE_SIZE), [api])
  const { data, error, loading, reload } = useLoad<CatalogProvider[]>(load)
  // Escolha vinda do "Agendar de novo": quando a lista chega e ainda não há escolha, seleciona o estúdio pedido (se ele ainda existir).
  useEffect(() => {
    if (!preselectId || selected || !data) return
    const wanted = data.find((p) => p.id === preselectId)
    if (wanted) onSelect(wanted)
  }, [preselectId, selected, data, onSelect])
  // Só existe um estúdio: não há o que escolher, então ele já vem escolhido (quem quiser pode trocar quando houver mais de um).
  useEffect(() => {
    if (selected || preselectId || !data || data.length !== 1) return
    onSelect(data[0])
  }, [selected, preselectId, data, onSelect])

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

function ServiceStep({
  provider,
  selected,
  disabled,
  preselectId,
  onSelect,
}: {
  provider: CatalogProvider
  selected: CatalogService | null
  disabled: boolean
  preselectId: string | null
  onSelect: (s: CatalogService) => void
}) {
  const { request } = useAuth()
  const api = useMemo(() => bookingApi(request), [request])
  const load = useCallback(() => api.services(provider.id), [api, provider.id])
  const { data, error, loading, reload } = useLoad<CatalogService[]>(load)
  // Serviço vindo do "Agendar de novo": seleciona quando a lista chega, se o serviço continua ativo.
  useEffect(() => {
    if (!preselectId || selected || !data) return
    const wanted = data.find((s) => s.id === preselectId)
    if (wanted) onSelect(wanted)
  }, [preselectId, selected, data, onSelect])

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
          <span className="choice-text">
            <span className="choice-name">{s.name}</span>
            {' '}<span className="choice-sep">·</span>{' '}<span className="choice-meta">{durationSummary(s)} · {priceSummary(s)}</span>
          </span>
        </label>
      ))}
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
        <strong>{service.name}</strong> ({durationForPet(service, pet?.size, pet?.coat)} min){pet && <> para <strong>{pet.name}</strong></>} em <strong>{provider.businessName}</strong>, {longDate(slot.localStart.slice(0, 10))}, às{' '}
        <strong>{slot.localStart.slice(11, 16)}</strong> (horário do negócio).
      </p>
      <p className="confirm-price">
        Valor: <strong>{(() => {
          const cents = priceForSize(service, pet?.size)
          return cents == null ? 'a combinar com o estúdio' : formatMoney(cents)
        })()}</strong>
        {pet?.size == null && (service.priceMediumCents != null || service.priceLargeCents != null) && <span className="hint"> Informe o porte do pet para ver o valor exato.</span>}
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
      <legend>3. Escolha o pet</legend>
      {error !== undefined && pets && <RefreshWarning onRetry={reload} />}
      {none && <p className="notice">Cadastre o pet que será atendido.</p>}
      {pets?.map((p) => (
        <label key={p.id} className="choice">
          <input type="radio" name="pet" disabled={disabled} checked={selected?.id === p.id} onChange={() => onSelect(p.id)} />
          <span className="choice-text">
            <span className="choice-name">{p.name}</span>
            {' '}<span className="choice-sep">·</span>{' '}<span className="choice-meta">{petDescription(p)}</span>
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
