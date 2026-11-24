import { useCallback, useMemo, useState } from 'react'
import { agendaApi, type Workplace } from '../../api/agenda'
import type { CatalogService, FreeSlot } from '../../api/booking'
import { counterBookingMessage, customersApi, type CounterAppointment } from '../../api/customers'
import { longDate, timeIn, todayIn } from '../../api/dates'
import { describeError } from '../../api/messages'
import { formatMoney } from '../../api/money'
import { whatsappLink } from '../../api/phone'
import { petDescription, type Pet, type PetInput } from '../../api/pets'
import { ApiError } from '../../api/client'
import { useAuth } from '../../auth/session'
import { DayStep } from '../customer/BookingSteps'
import { PetForm } from '../customer/PetForm'
import { Loading } from '../Loading'
import { useLoad } from '../useLoad'
import { useSingleFlight } from '../useSingleFlight'

/** Quem a equipe agenda: um tutor que o PRÓPRIO estúdio cadastrou (ADR 0033). */
export interface CounterTarget {
  id: string
  name: string
  phone?: string | null
}

/**
 * Agendar pelo balcão (ADR 0033): a equipe escolhe o pet, o serviço, o dia e o horário por um tutor que ligou ou chamou no WhatsApp. As regras (expediente, folgas,
 * duração pelo pet, reserva dupla, sinal) são as da API, as mesmas do tutor; aqui só se monta o pedido. Ao terminar, mostra a mensagem de confirmação pronta para o
 * WhatsApp (o app não envia: quem envia é a equipe, tocando no link).
 */
export function CounterBookingPanel({ customer, onClose }: { customer: CounterTarget; onClose: () => void }) {
  const { request } = useAuth()
  const api = useMemo(() => customersApi(request), [request])
  const agenda = useMemo(() => agendaApi(request), [request])
  const workplaceLoad = useCallback(() => agenda.workplace(), [agenda])
  const servicesLoad = useCallback(() => api.services(), [api])
  const petsLoad = useCallback(() => api.pets(customer.id), [api, customer.id])
  const workplace = useLoad<Workplace>(workplaceLoad)
  const services = useLoad<CatalogService[]>(servicesLoad)
  const pets = useLoad<Pet[]>(petsLoad)

  const [petId, setPetId] = useState('')
  const [addingPet, setAddingPet] = useState(false)
  const [serviceId, setServiceId] = useState('')
  const [date, setDate] = useState<string | null>(null)
  const [slot, setSlot] = useState<FreeSlot | null>(null)
  const [problem, setProblem] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [booked, setBooked] = useState<{ appointment: CounterAppointment; petName: string; serviceName: string } | null>(null)
  const once = useSingleFlight()

  if ((workplace.loading && !workplace.data) || (services.loading && !services.data) || (pets.loading && !pets.data)) return <Loading />
  const failure = workplace.error ?? services.error ?? pets.error
  if (failure !== undefined && (!workplace.data || !services.data || !pets.data)) {
    return (
      <p role="alert" className="error">
        {describeError(failure)}
      </p>
    )
  }
  if (!workplace.data || !services.data || !pets.data) return null

  const tz = workplace.data.timeZone
  const today = todayIn(tz)
  const day = date ?? today
  const list = pets.data
  const chosenPet = list.find((p) => p.id === petId)
  const chosenService = services.data.find((s) => s.id === serviceId)

  async function addPet(input: PetInput) {
    const created = await api.createPet(customer.id, input)
    await pets.reload()
    setPetId(created.id)
    setSlot(null)
    setAddingPet(false)
  }

  async function confirm() {
    if (!chosenPet || !chosenService || !slot) return
    const pet = chosenPet
    const service = chosenService
    const chosen = slot
    await once(async () => {
      setProblem(null)
      setBusy(true)
      try {
        const appointment = await api.book(customer.id, { serviceId: service.id, localStart: chosen.localStart, petId: pet.id })
        setBooked({ appointment, petName: pet.name, serviceName: service.name })
      } catch (e) {
        setSlot(null) // o horário pode ter sido tomado: escolha de novo, com a lista atualizada
        setProblem(e instanceof ApiError && e.status === 409 ? 'Esse horário acabou de ser ocupado. Escolha outro.' : describeError(e))
      } finally {
        setBusy(false)
      }
    })
  }

  if (booked) {
    const { appointment } = booked
    const when = `${longDate(localDay(appointment.startsAt, tz))} às ${timeIn(appointment.startsAt, tz)}`
    const message = counterBookingMessage({
      tutorName: customer.name,
      studioName: workplace.data.businessName,
      petName: booked.petName,
      when,
      depositCents: appointment.depositCents ?? null,
    })
    return (
      <section className="card counter" aria-labelledby="counter-title">
        <h2 id="counter-title">Horário marcado</h2>
        <p role="status" className="notice">
          {booked.serviceName} de {booked.petName} para {customer.name}: {when}.
        </p>
        {appointment.depositCents != null && appointment.depositStatus === 'REQUESTED' && (
          <p className="notice">
            Este estúdio pede sinal de {formatMoney(appointment.depositCents)} por Pix. Combine com o tutor e marque como recebido na Agenda.
          </p>
        )}
        <span className="actions">
          {customer.phone && (
            <a className="button" href={whatsappLink(customer.phone, message)} target="_blank" rel="noopener noreferrer">
              Confirmar pelo WhatsApp
              <span className="pp-sr"> ({customer.name})</span>
            </a>
          )}
          <button type="button" className={customer.phone ? 'button secondary' : 'button'} onClick={onClose}>
            Concluir
          </button>
        </span>
      </section>
    )
  }

  const noPets = list.length === 0
  return (
    <section className="card counter" aria-labelledby="counter-title">
      <h2 id="counter-title">Agendar para {customer.name}</h2>

      <fieldset className="step">
        <legend>1. Pet</legend>
        {!noPets && (
          <div className="field">
            <label htmlFor="counter-pet">Pet</label>
            <select
              id="counter-pet"
              value={petId}
              onChange={(e) => {
                setPetId(e.target.value)
                setSlot(null)
              }}
            >
              <option value="">Escolha o pet</option>
              {list.map((p) => (
                <option key={p.id} value={p.id}>
                  {p.name} ({petDescription(p)})
                </option>
              ))}
            </select>
          </div>
        )}
        {(noPets || addingPet) && (
          <>
            {noPets && <p className="hint">{customer.name} ainda não tem pet cadastrado. Cadastre para continuar.</p>}
            <PetForm idPrefix="counter-pet-new" submitLabel="Cadastrar pet" busyLabel="Cadastrando…" onSubmit={addPet} onCancel={noPets ? undefined : () => setAddingPet(false)} />
          </>
        )}
        {!noPets && !addingPet && (
          <button type="button" className="button secondary" onClick={() => setAddingPet(true)}>
            Cadastrar outro pet
          </button>
        )}
      </fieldset>

      {chosenPet && (
        <fieldset className="step">
          <legend>2. Serviço</legend>
          <div className="field">
            <label htmlFor="counter-service">Serviço</label>
            <select
              id="counter-service"
              value={serviceId}
              onChange={(e) => {
                setServiceId(e.target.value)
                setSlot(null)
              }}
            >
              <option value="">Escolha o serviço</option>
              {services.data.map((s) => (
                <option key={s.id} value={s.id}>
                  {s.name}
                </option>
              ))}
            </select>
          </div>
          {services.data.length === 0 && <p className="notice">O estúdio ainda não cadastrou serviços. Cadastre em Serviços.</p>}
        </fieldset>
      )}

      {chosenPet && chosenService && (
        <>
          <DayStep
            provider={{ id: workplace.data.providerId, businessName: workplace.data.businessName, timeZone: tz }}
            date={day}
            disabled={busy}
            number={3}
            onChange={(d) => {
              setDate(d)
              setSlot(null)
            }}
          />
          <CounterSlots customerId={customer.id} serviceId={chosenService.id} petId={chosenPet.id} date={day} selected={slot} disabled={busy} onSelect={setSlot} />
        </>
      )}

      {problem && (
        <p role="alert" className="error">
          {problem}
        </p>
      )}
      <span className="actions">
        <button type="button" className="button" disabled={busy || !slot} onClick={confirm}>
          {busy ? 'Agendando…' : slot ? `Agendar às ${slot.localStart.slice(11, 16)}` : 'Escolha um horário'}
        </button>
        <button type="button" className="button secondary" disabled={busy} onClick={onClose}>
          Cancelar
        </button>
      </span>
    </section>
  )
}

/** O dia (AAAA-MM-DD) de um instante, no fuso do estúdio. */
function localDay(instant: string, timeZone: string): string {
  return new Intl.DateTimeFormat('en-CA', { timeZone, year: 'numeric', month: '2-digit', day: '2-digit' }).format(new Date(instant))
}

function CounterSlots({ customerId, serviceId, petId, date, selected, disabled, onSelect }: { customerId: string; serviceId: string; petId: string; date: string; selected: FreeSlot | null; disabled: boolean; onSelect: (s: FreeSlot) => void }) {
  const { request } = useAuth()
  const api = useMemo(() => customersApi(request), [request])
  const load = useCallback(() => api.slots(customerId, serviceId, date, petId), [api, customerId, serviceId, date, petId])
  const { data, error, loading, reload } = useLoad<FreeSlot[]>(load)
  if (loading && !data) return <Loading>Carregando horários…</Loading>
  if (error !== undefined && !data) {
    return (
      <p role="alert" className="error">
        {describeError(error)}
      </p>
    )
  }
  return (
    <fieldset className="step">
      <legend>4. Horário</legend>
      {data && data.length === 0 && <p className="notice">Nenhum horário livre neste dia (ou o estúdio fecha nele). Tente outro dia.</p>}
      <div className="slots" role="group" aria-label="Horários livres">
        {data?.map((s) => (
          <button key={s.localStart} type="button" className={selected?.localStart === s.localStart ? 'button' : 'button secondary'} aria-pressed={selected?.localStart === s.localStart} disabled={disabled} onClick={() => onSelect(s)}>
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
