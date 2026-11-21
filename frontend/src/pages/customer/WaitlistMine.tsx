import { useCallback, useMemo, useState } from 'react'
import { longDate } from '../../api/dates'
import { describeError } from '../../api/messages'
import { waitlistApi, type WaitlistMine as Entry } from '../../api/waitlist'
import { useAuth } from '../../auth/session'
import { useLoad } from '../useLoad'
import { useSingleFlight } from '../useSingleFlight'

/**
 * As entradas do tutor na lista de espera (ADR 0022), em Meus agendamentos. Só aparece quando há entrada; se não der para carregar, a tela
 * principal continua valendo (esta seção é complementar). Sair da lista é um clique e a API confere o dono.
 */
export function WaitlistMine() {
  const { request } = useAuth()
  const api = useMemo(() => waitlistApi(request), [request])
  const load = useCallback(() => api.mine(), [api])
  const { data, reload } = useLoad<Entry[]>(load)
  const [error, setError] = useState<string | null>(null)
  const [busyId, setBusyId] = useState<string | null>(null)
  const once = useSingleFlight()

  if (!data || data.length === 0) return null

  async function leave(entry: Entry) {
    await once(async () => {
      setError(null)
      setBusyId(entry.id)
      try {
        await api.remove(entry.id)
        await reload()
      } catch (e) {
        setError(describeError(e))
        await reload().catch(() => undefined)
      } finally {
        setBusyId(null)
      }
    })
  }

  return (
    <section aria-label="Lista de espera" className="waitlist-mine">
      <h2>Lista de espera</h2>
      <p className="hint">Se vagar um horário nesses dias, o estúdio chama você pelo WhatsApp. Isso não reserva o horário.</p>
      <p role="alert" className="error" hidden={!error}>
        {error}
      </p>
      <ul className="rows">
        {data.map((entry) => (
          <li key={entry.id} className="row">
            <span>
              <strong>{longDate(entry.date)}</strong> · {entry.serviceName ?? 'Serviço'} em {entry.businessName}
              {entry.petName && <> · {entry.petName}</>}
            </span>
            <span className="actions">
              <button type="button" className="button secondary" disabled={busyId !== null} onClick={() => leave(entry)}>
                {busyId === entry.id ? 'Saindo…' : 'Sair da lista'}
              </button>
            </span>
          </li>
        ))}
      </ul>
    </section>
  )
}
