import { useCallback, useEffect, useMemo, useRef, useState, type FormEvent } from 'react'
import { adminApi, type AdminRole, type AdminUser } from '../../api/admin'
import { ApiError } from '../../api/client'
import { describeError } from '../../api/messages'
import { generatePassword } from '../../auth/generatePassword'
import { validateNewPassword } from '../../auth/passwordPolicy'
import { useAuth } from '../../auth/session'
import { Field } from '../Field'
import { RefreshWarning } from '../RefreshWarning'
import { useLoad } from '../useLoad'
import { useSingleFlight } from '../useSingleFlight'
import { PasswordDelivery } from './PasswordDelivery'

const PAGE_SIZE = 20
const ROLE_LABEL: Record<AdminRole, string> = { ADMIN: 'Administrador', PROVIDER: 'Prestador', ATTENDANT: 'Atendente', CUSTOMER: 'Cliente' }

/**
 * Contas (só ADMIN): busca por nome ou e-mail, filtro por perfil, ativar, desativar e redefinir a senha. A paginação da API é por
 * deslocamento (ADR 0007 item 7): trocar a busca ou o filtro volta para a primeira página; ativar, desativar e redefinir não mudam
 * quem está na lista nem a ordem, então a página atual é recarregada.
 */
export function UsersPage() {
  const [draft, setDraft] = useState({ q: '', role: '' })
  const [applied, setApplied] = useState({ q: '', role: '' })
  const [page, setPage] = useState(0)

  function search(event: FormEvent) {
    event.preventDefault()
    setApplied({ q: draft.q.trim(), role: draft.role })
    setPage(0)
  }

  return (
    <section className="card">
      <h1>Contas</h1>
      <form onSubmit={search} role="search" aria-label="Buscar contas" noValidate>
        <Field label="Nome ou e-mail" name="users-q" autoComplete="off" maxLength={100} value={draft.q} onChange={(e) => setDraft((d) => ({ ...d, q: e.target.value }))} />
        <div className="field">
          <label htmlFor="users-role">Perfil</label>
          <select id="users-role" value={draft.role} onChange={(e) => setDraft((d) => ({ ...d, role: e.target.value }))}>
            <option value="">Todos os perfis</option>
            {(Object.keys(ROLE_LABEL) as AdminRole[]).map((role) => (
              <option key={role} value={role}>
                {ROLE_LABEL[role]}
              </option>
            ))}
          </select>
        </div>
        <button type="submit" className="button">
          Buscar
        </button>
      </form>
      {/* A chave é a consulta e a página: trocar qualquer uma refaz a lista do zero (nunca aparece a lista de outra busca). */}
      <UserList key={`${applied.q}|${applied.role}|${page}`} query={{ q: applied.q, role: applied.role }} page={page} onPage={setPage} />
    </section>
  )
}

/**
 * O que foi confirmado por esta tela. `seq` é o número da confirmação (1, 2, 3...). Cada leitura guarda quantas confirmações já existiam
 * QUANDO ELA COMEÇOU (`startedSeq`): a confirmação só cede a uma leitura iniciada DEPOIS dela. Uma leitura que já estava em andamento
 * antes (e pode trazer o estado antigo) nunca a desfaz, mesmo que termine depois.
 */
interface Confirmed {
  active?: boolean
  mustChangePassword?: boolean
  seq: number
}

interface Loaded {
  users: AdminUser[]
  startedSeq: number
}

function UserList({ query, page, onPage }: { query: { q: string; role: string }; page: number; onPage: (page: number) => void }) {
  const { request, user: me } = useAuth()
  const api = useMemo(() => adminApi(request), [request])
  const seq = useRef(0)
  const load = useCallback(async (): Promise<Loaded> => {
    const startedSeq = seq.current // quantas confirmações já existiam quando ESTA leitura começou
    return { users: await api.users({ ...query, page, size: PAGE_SIZE }), startedSeq }
  }, [api, query, page])
  const { data, error, loading, reload } = useLoad<Loaded>(load)
  const [notice, setNotice] = useState<string | null>(null)
  // O erro mora AQUI, não na linha: a lista pode ser refeita e a linha, mudar.
  const [problem, setProblem] = useState<string | null>(null)
  const [confirmed, setConfirmed] = useState<ReadonlyMap<string, Confirmed>>(new Map())
  const [delivered, setDelivered] = useState<{ name: string; email: string; password: string } | null>(null)

  // Registra a confirmação com o próximo número; só uma leitura iniciada depois dele passa a mandar.
  const confirmAt = useCallback((id: string, change: Omit<Confirmed, 'seq'>) => {
    seq.current += 1
    const mine = seq.current
    setConfirmed((current) => new Map(current).set(id, { ...current.get(id), ...change, seq: mine }))
  }, [])

  if (loading && !data) return <p role="status">Carregando…</p>
  if (error && !data) return <p role="alert" className="error">{describeError(error)}</p>
  return (
    <>
      {error !== undefined && data && <RefreshWarning onRetry={reload} />}
      <p role="status" className="notice" hidden={!notice}>
        {notice}
      </p>
      <p role="alert" className="error" hidden={!problem}>
        {problem}
      </p>
      {delivered && (
        <PasswordDelivery password={delivered.password} onDone={() => setDelivered(null)}>
          Senha de <strong>{delivered.name}</strong> ({delivered.email}) redefinida. Entregue esta senha provisória por um canal seguro: ela só vale para o próximo acesso e{' '}
          <strong>não será mostrada de novo</strong>.
        </PasswordDelivery>
      )}
      {data && data.users.length === 0 && <p className="notice">Nenhuma conta encontrada.</p>}
      <ul className="rows">
        {data?.users.map((item) => {
          const c = confirmed.get(item.id)
          const current = c && data.startedSeq < c.seq ? c : undefined
          return (
            <UserRow
              key={item.id}
              item={{ ...item, active: current?.active ?? item.active, mustChangePassword: current?.mustChangePassword ?? item.mustChangePassword }}
              isMe={me?.id === item.id}
              onChanged={reload}
              onConfirmed={(change) => confirmAt(item.id, change)}
              onDelivered={setDelivered}
              setNotice={setNotice}
              setProblem={setProblem}
            />
          )
        })}
      </ul>
      <p className="actions">
        <button type="button" className="button secondary" disabled={page === 0} onClick={() => onPage(page - 1)}>
          Página anterior
        </button>
        <span className="hint">Página {page + 1}</span>
        <button type="button" className="button secondary" disabled={!data || data.users.length < PAGE_SIZE} onClick={() => onPage(page + 1)}>
          Próxima página
        </button>
      </p>
    </>
  )
}

type Pending = 'deactivate' | 'reset' | null

function UserRow({
  item,
  isMe,
  onChanged,
  onConfirmed,
  onDelivered,
  setNotice,
  setProblem,
}: {
  item: AdminUser
  isMe: boolean
  onChanged: () => Promise<void>
  onConfirmed: (change: { active?: boolean; mustChangePassword?: boolean }) => void
  onDelivered: (value: { name: string; email: string; password: string }) => void
  setNotice: (message: string | null) => void
  setProblem: (message: string | null) => void
}) {
  const { request } = useAuth()
  const api = useMemo(() => adminApi(request), [request])
  const [pending, setPending] = useState<Pending>(null)
  const [password, setPassword] = useState('')
  const [reveal, setReveal] = useState(false)
  const [passwordError, setPasswordError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const once = useSingleFlight()
  // Foco: a confirmação abre no campo/botão seguro e, ao fechar, volta ao botão que a abriu; quando a ação termina com a linha ainda aqui, vai para a linha.
  const rowRef = useRef<HTMLLIElement>(null)
  const deactivateRef = useRef<HTMLButtonElement>(null)
  const resetRef = useRef<HTMLButtonElement>(null)
  const backRef = useRef<HTMLButtonElement>(null)
  const passwordRef = useRef<HTMLInputElement>(null)
  const last = useRef<Pending>(null)
  useEffect(() => {
    if (pending === 'deactivate') backRef.current?.focus()
    else if (pending === 'reset') passwordRef.current?.focus()
    else if (last.current === 'deactivate') deactivateRef.current?.focus()
    else if (last.current === 'reset') resetRef.current?.focus()
    last.current = pending
  }, [pending])

  async function act(run: () => Promise<void>) {
    await once(async () => {
      setProblem(null)
      setNotice(null)
      setBusy(true)
      try {
        await run()
        last.current = null
        setPending(null)
        rowRef.current?.focus()
        await onChanged()
      } catch (e) {
        setProblem(describeError(e))
        setPending(null)
        // A conta não existe mais: a lista é refeita.
        if (e instanceof ApiError && e.status === 404) await onChanged().catch(() => undefined)
      } finally {
        setBusy(false)
      }
    })
  }

  const deactivate = () =>
    act(async () => {
      await api.deactivate(item.id)
      onConfirmed({ active: false })
      setNotice(`A conta de ${item.name} foi desativada.`)
    })
  const activate = () =>
    act(async () => {
      await api.activate(item.id)
      onConfirmed({ active: true })
      setNotice(`A conta de ${item.name} foi ativada.`)
    })

  async function reset(event: FormEvent) {
    event.preventDefault()
    const invalid = validateNewPassword(password)
    if (invalid) {
      setPasswordError(invalid)
      passwordRef.current?.focus()
      return
    }
    setPasswordError(null)
    const sent = password
    await act(async () => {
      await api.resetPassword(item.id, sent)
      onConfirmed({ mustChangePassword: true })
      onDelivered({ name: item.name, email: item.email, password: sent })
      setPassword('')
      setReveal(false)
    })
  }

  return (
    <li className="row" ref={rowRef} tabIndex={-1}>
      <span>
        <strong>{item.name}</strong> · {item.email} · {ROLE_LABEL[item.role]}{' '}
        <span className="badge">{item.active ? 'Ativa' : 'Inativa'}</span>
        {item.mustChangePassword && <span className="badge"> Precisa trocar a senha</span>}
        {isMe && <span className="hint"> (você)</span>}
      </span>
      <span className="actions">
        {pending === null && (
          <>
            {item.active && !isMe && (
              <button type="button" className="button secondary" ref={deactivateRef} disabled={busy} onClick={() => setPending('deactivate')}>
                Desativar
              </button>
            )}
            {!item.active && (
              <button type="button" className="button secondary" disabled={busy} onClick={activate}>
                {busy ? 'Aguarde…' : 'Ativar'}
              </button>
            )}
            <button type="button" className="button secondary" ref={resetRef} disabled={busy} onClick={() => setPending('reset')}>
              Redefinir senha
            </button>
          </>
        )}
        {pending === 'deactivate' && (
          <>
            <span>Desativar a conta de {item.name}? Ela perde o acesso na hora e só um administrador reativa.</span>
            <button type="button" className="button" disabled={busy} onClick={deactivate}>
              {busy ? 'Aguarde…' : 'Confirmar desativação'}
            </button>
            <button type="button" className="button secondary" ref={backRef} disabled={busy} onClick={() => setPending(null)}>
              Voltar
            </button>
          </>
        )}
      </span>
      {pending === 'reset' && (
        <form onSubmit={reset} noValidate>
          <Field
            label={`Nova senha provisória de ${item.name}`}
            name={`reset-${item.id}`}
            type={reveal ? 'text' : 'password'}
            autoComplete="new-password"
            disabled={busy}
            hint="Pelo menos 10 caracteres. A pessoa será obrigada a trocá-la no próximo acesso e sai de todos os aparelhos."
            value={password}
            error={passwordError}
            ref={passwordRef}
            onChange={(e) => {
              setPassword(e.target.value)
              setPasswordError(null)
            }}
          />
          <p className="actions">
            <button
              type="button"
              className="button secondary"
              disabled={busy}
              onClick={() => {
                setPassword(generatePassword())
                setReveal(true)
                setPasswordError(null)
              }}
            >
              Gerar senha
            </button>
            <button type="submit" className="button" disabled={busy}>
              {busy ? 'Aguarde…' : 'Redefinir senha'}
            </button>
            <button
              type="button"
              className="button secondary"
              disabled={busy}
              onClick={() => {
                setPassword('')
                setReveal(false)
                setPasswordError(null)
                setPending(null)
              }}
            >
              Voltar
            </button>
          </p>
        </form>
      )}
    </li>
  )
}
