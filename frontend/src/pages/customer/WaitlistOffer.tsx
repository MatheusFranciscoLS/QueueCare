import { useMemo, useState } from 'react'
import { Link } from 'react-router-dom'
import { longDate } from '../../api/dates'
import { describeError } from '../../api/messages'
import { waitlistApi } from '../../api/waitlist'
import { useAuth } from '../../auth/session'
import { useSingleFlight } from '../useSingleFlight'

/**
 * Quando o dia não tem horário livre (ADR 0022): em vez de um beco sem saída, o tutor pede "avise-me se vagar um horário". Não reserva nada
 * e não promete prioridade: o estúdio vê a lista e chama pelo WhatsApp. Pedir de novo é seguro (a API devolve a mesma entrada).
 */
export function WaitlistOffer({ providerId, serviceId, petId, date, disabled }: { providerId: string; serviceId: string; petId: string; date: string; disabled: boolean }) {
  const { request } = useAuth()
  const api = useMemo(() => waitlistApi(request), [request])
  const [joined, setJoined] = useState(false)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const once = useSingleFlight()

  async function join() {
    await once(async () => {
      setError(null)
      setBusy(true)
      try {
        await api.join({ providerId, serviceId, petId, date })
        setJoined(true)
      } catch (e) {
        setError(describeError(e))
      } finally {
        setBusy(false)
      }
    })
  }

  if (joined) {
    return (
      <p role="status" className="notice waitlist-offer">
        Pronto! Você está na lista de espera de {longDate(date)}. Se vagar um horário, o estúdio chama você pelo WhatsApp.{' '}
        <Link to="/meus-agendamentos">Ver minha lista de espera</Link>
      </p>
    )
  }
  return (
    <div className="waitlist-offer">
      <p className="hint">Quer este dia? Entre na lista de espera e o estúdio avisa se vagar um horário.</p>
      <button type="button" className="button secondary" disabled={disabled || busy} onClick={join}>
        {busy ? 'Entrando…' : 'Avise-me se vagar um horário neste dia'}
      </button>
      <p role="alert" className="error" hidden={!error}>
        {error}
      </p>
    </div>
  )
}
