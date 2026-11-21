import { useCallback, useMemo, useState } from 'react'
import { describeError } from '../../api/messages'
import { calledStamp, waitlistApi, waitlistCallLink, type WaitlistStaff } from '../../api/waitlist'
import { useAuth } from '../../auth/session'
import { PetAvatar } from '../../ui/components'
import { useLoad } from '../useLoad'
import { useSingleFlight } from '../useSingleFlight'

/**
 * Quem espera este dia (ADR 0022), na Agenda do dia: por ordem de entrada, com a duração prevista PARA O PET e o WhatsApp com mensagem pronta.
 * Só aparece quando há alguém. O clique em "Chamar" abre o WhatsApp e grava quando; "Tirar" retira da lista. A lista não reserva nada: a equipe
 * combina o horário na conversa e agenda pela própria Agenda.
 */
export function WaitlistDay({ date, timeZone, studioName }: { date: string; timeZone: string; studioName: string }) {
  const { request } = useAuth()
  const api = useMemo(() => waitlistApi(request), [request])
  const load = useCallback(() => api.day(date), [api, date])
  const { data, reload } = useLoad<WaitlistStaff[]>(load)
  const [error, setError] = useState<string | null>(null)
  const [busyId, setBusyId] = useState<string | null>(null)
  const once = useSingleFlight()

  if (!data || data.length === 0) return null

  async function markCalled(entry: WaitlistStaff) {
    setError(null)
    try {
      await api.called(entry.id)
      await reload()
    } catch (e) {
      setError(describeError(e))
    }
  }

  async function remove(entry: WaitlistStaff) {
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
    <section aria-label="Lista de espera" className="waitlist-day">
      <h2>Lista de espera ({data.length})</h2>
      <p className="hint">Quem pediu aviso se vagar um horário neste dia, por ordem de entrada. A lista não reserva: combine o horário na conversa.</p>
      <p role="alert" className="error" hidden={!error}>
        {error}
      </p>
      <ul className="rows">
        {data.map((entry) => {
          const link = waitlistCallLink(entry, studioName, date)
          const who = entry.customerName ?? 'Tutor'
          return (
            <li key={entry.id} className="row">
              {entry.petSpecies && <PetAvatar species={entry.petSpecies} />}
              <span>
                <strong>{entry.petName ?? 'Pet'}</strong> · {who} · {entry.serviceName ?? 'Serviço'}, cerca de {entry.durationMinutes} min
                {entry.calledAt && <span className="appt-reminded"> · Chamado em {calledStamp(entry.calledAt, timeZone)}</span>}
              </span>
              <span className="actions">
                {link && (
                  <a className="button secondary" href={link} target="_blank" rel="noopener noreferrer" aria-label={`Chamar ${who} pelo WhatsApp`} onClick={() => void markCalled(entry)}>
                    Chamar
                  </a>
                )}
                <button type="button" className="button secondary" disabled={busyId !== null} aria-label={`Tirar da lista: ${who}`} onClick={() => remove(entry)}>
                  {busyId === entry.id ? 'Tirando…' : 'Tirar da lista'}
                </button>
              </span>
            </li>
          )
        })}
      </ul>
    </section>
  )
}
