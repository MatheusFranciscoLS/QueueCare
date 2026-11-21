import { useCallback, useEffect, useMemo, useState, type FormEvent } from 'react'
import { agendaApi, type Workplace } from '../../api/agenda'
import { customersApi, customerSummary, temporaryPasswordMessage, type StudioCustomer } from '../../api/customers'
import { describeError } from '../../api/messages'
import { whatsappLink } from '../../api/phone'
import { useAuth } from '../../auth/session'
import { Loading } from '../Loading'
import { RefreshWarning } from '../RefreshWarning'
import { useLoad } from '../useLoad'
import { useSingleFlight } from '../useSingleFlight'
import { ContactLink } from './ContactLink'

/** Espera depois da última tecla antes de buscar (o balcão digita rápido: uma busca só, não uma por letra). */
const TYPING_DELAY_MS = 300

/**
 * Clientes (ADR 0025): a equipe acha o tutor no balcão por nome, telefone ou nome do pet, vendo só quem o estúdio já atendeu. O DONO também gera a senha
 * temporária de um tutor que esqueceu a dele (sem e-mail: custo zero): ela aparece UMA vez, o dono a passa pelo WhatsApp, e o tutor cria outra ao entrar.
 */
export function CustomersPage() {
  const { request, user } = useAuth()
  const api = useMemo(() => customersApi(request), [request])
  const agenda = useMemo(() => agendaApi(request), [request])
  const [typed, setTyped] = useState('')
  const [query, setQuery] = useState('')
  const workplaceLoad = useCallback(() => agenda.workplace(), [agenda])
  const { data: workplace } = useLoad<Workplace>(workplaceLoad)
  const load = useCallback(() => api.search(query), [api, query])
  const { data, error, loading, reload } = useLoad<StudioCustomer[]>(load)
  const isOwner = user?.role === 'PROVIDER'

  useEffect(() => {
    const timer = setTimeout(() => setQuery(typed.trim()), TYPING_DELAY_MS)
    return () => clearTimeout(timer)
  }, [typed])

  function submit(event: FormEvent) {
    event.preventDefault()
    setQuery(typed.trim()) // Enter busca na hora, sem esperar a pausa
  }

  return (
    <section className="card customers">
      <h1>Clientes</h1>
      <p className="hint">Tutores que o estúdio já atendeu. Busque por nome, telefone ou nome do pet.</p>
      <form role="search" onSubmit={submit} className="customers__search">
        <div className="field">
          <label htmlFor="customer-search">Buscar cliente</label>
          <input id="customer-search" type="search" autoComplete="off" value={typed} maxLength={80} onChange={(e) => setTyped(e.target.value)} />
        </div>
        <button type="submit" className="button">
          Buscar
        </button>
      </form>

      {loading && !data && <Loading />}
      {error !== undefined && data && <RefreshWarning onRetry={reload} />}
      {error !== undefined && !data && (
        <p role="alert" className="error">
          {describeError(error)}
        </p>
      )}
      {data && data.length === 0 && (
        <p className="notice">
          {query ? <>Nenhum cliente encontrado para “{query}”.</> : 'Ainda não há clientes: eles aparecem aqui depois do primeiro agendamento.'}
        </p>
      )}
      {data && data.length > 0 && (
        <ul className="rows customers__list">
          {data.map((customer) => (
            <CustomerRow key={customer.id} customer={customer} isOwner={isOwner} timeZone={workplace?.timeZone} />
          ))}
        </ul>
      )}
    </section>
  )
}

function lastVisit(instant: string | null | undefined, timeZone: string | undefined): string | null {
  if (!instant) return null
  return new Intl.DateTimeFormat('pt-BR', { timeZone, day: '2-digit', month: '2-digit', year: 'numeric' }).format(new Date(instant))
}

function CustomerRow({ customer, isOwner, timeZone }: { customer: StudioCustomer; isOwner: boolean; timeZone: string | undefined }) {
  const { request } = useAuth()
  const api = useMemo(() => customersApi(request), [request])
  const [step, setStep] = useState<'idle' | 'confirm' | 'done'>('idle')
  const [password, setPassword] = useState<string | null>(null)
  const [copied, setCopied] = useState<'yes' | 'no' | null>(null)
  const [problem, setProblem] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const once = useSingleFlight()
  const last = lastVisit(customer.lastAppointmentAt, timeZone)

  async function generate() {
    await once(async () => {
      setProblem(null)
      setBusy(true)
      try {
        const result = await api.resetPassword(customer.id)
        setPassword(result.temporaryPassword)
        setStep('done')
      } catch (e) {
        setProblem(describeError(e))
      } finally {
        setBusy(false)
      }
    })
  }

  async function copy() {
    if (!password) return
    try {
      await navigator.clipboard.writeText(password)
      setCopied('yes')
    } catch {
      setCopied('no')
    }
  }

  function close() {
    setPassword(null) // a senha some da tela e da memória: só dá para gerar outra
    setCopied(null)
    setStep('idle')
  }

  return (
    <li className="row customer">
      <span className="customer__info">
        <strong className="customer__name">{customer.name}</strong>
        {customer.phone && <ContactLink phone={customer.phone} name={customer.name} />}
        <span className="customer__meta">
          {customerSummary(customer)}
          {last ? ` · último agendamento em ${last}` : ''}
        </span>
        {customer.pets.length > 0 && <span className="customer__pets">Pets: {customer.pets.join(', ')}</span>}
      </span>

      {isOwner && step === 'idle' && (
        <span className="actions">
          <button type="button" className="button secondary" aria-label={`Redefinir senha de ${customer.name}`} onClick={() => setStep('confirm')}>
            Redefinir senha
          </button>
        </span>
      )}

      {isOwner && step === 'confirm' && (
        <span className="actions customer__confirm">
          <span>
            Gerar uma senha temporária para {customer.name}? A senha atual deixa de valer e a pessoa cria outra ao entrar.
          </span>
          <button type="button" className="button" disabled={busy} onClick={generate}>
            {busy ? 'Gerando…' : 'Gerar senha temporária'}
          </button>
          <button type="button" className="button secondary" disabled={busy} onClick={() => setStep('idle')}>
            Voltar
          </button>
        </span>
      )}

      {problem && (
        <p role="alert" className="error customer__problem">
          {problem}
        </p>
      )}

      {step === 'done' && password && (
        <div className="customer__password" role="group" aria-label={`Senha temporária de ${customer.name}`}>
          <p>
            Senha temporária de {customer.name}: <code className="customer__code">{password}</code>
          </p>
          <p className="hint">Ela aparece só agora. Passe para o tutor, que cria uma nova senha ao entrar.</p>
          <span className="actions">
            <button type="button" className="button secondary" onClick={copy}>
              Copiar senha
            </button>
            {customer.phone && (
              <a className="button secondary" href={whatsappLink(customer.phone, temporaryPasswordMessage(customer.name, password))} target="_blank" rel="noopener noreferrer">
                Enviar pelo WhatsApp
                <span className="pp-sr"> ({customer.name})</span>
              </a>
            )}
            <button type="button" className="button" onClick={close}>
              Concluir
            </button>
          </span>
          <p role="status" className="notice" hidden={!copied}>
            {copied === 'yes' ? 'Senha copiada.' : 'Não deu para copiar: selecione a senha acima.'}
          </p>
        </div>
      )}
    </li>
  )
}
