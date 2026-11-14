import { useCallback, useMemo, useState, type FormEvent } from 'react'
import { Link } from 'react-router-dom'
import { isNotConfigured } from '../../api/errors'
import { describeError } from '../../api/messages'
import { staffApi, type StaffMember } from '../../api/staff'
import { generatePassword } from '../../auth/generatePassword'
import { validateNewPassword } from '../../auth/passwordPolicy'
import { useAuth } from '../../auth/session'
import { Field } from '../Field'
import { RefreshWarning } from '../RefreshWarning'
import { useFocusFirstInvalid } from '../useFocusFirstInvalid'
import { useLoad } from '../useLoad'
import { useSingleFlight } from '../useSingleFlight'
import { Loading } from '../Loading'

export function TeamPage() {
  const { request } = useAuth()
  const api = useMemo(() => staffApi(request), [request])
  const load = useCallback(() => api.list(), [api])
  const { data, error, loading, reload } = useLoad<StaffMember[]>(load)
  // Um aviso por ação concluída (ex.: "Conta criada"); fica aqui porque as listas e formulários são refeitos ao recarregar.
  const [notice, setNotice] = useState<string | null>(null)
  // Desvinculados/desativados com SUCESSO nesta tela: somem na hora, mesmo que o GET seguinte falhe (senão a lista antiga ainda
  // ofereceria ações que só receberiam 404).
  const [removed, setRemoved] = useState<ReadonlySet<string>>(new Set())

  if (loading && !data) return <Loading />
  if (isNotConfigured(error)) {
    return (
      <section className="card">
        <h1>Equipe</h1>
        <p className="notice">
          Cadastre primeiro o seu negócio em <Link to="/meu-negocio">Meu negócio</Link>.
        </p>
      </section>
    )
  }
  if (error && !data) return <p role="alert" className="error">{describeError(error)}</p>

  const members = data?.filter((m) => !removed.has(m.userId))
  return (
    <section className="card">
      <h1>Equipe</h1>
      {error !== undefined && data && <RefreshWarning onRetry={reload} />}
      <p role="status" className="notice" hidden={!notice}>
        {notice}
      </p>
      {members && members.length === 0 && <p className="notice">Você ainda não tem atendentes. Crie uma conta ou vincule alguém abaixo.</p>}
      <ul className="rows">
        {members?.map((member) => (
          <MemberRow key={member.userId} member={member} onChanged={reload} onRemoved={() => setRemoved((current) => new Set(current).add(member.userId))} setNotice={setNotice} />
        ))}
      </ul>
      <h2>Criar conta de atendente</h2>
      <CreateAccount onCreated={reload} setNotice={setNotice} />
      <h2>Vincular um atendente que já tem conta</h2>
      <LinkExisting onLinked={reload} setNotice={setNotice} />
    </section>
  )
}

type Notice = (message: string | null) => void

function CreateAccount({ onCreated, setNotice }: { onCreated: () => Promise<void>; setNotice: Notice }) {
  const { request } = useAuth()
  const api = useMemo(() => staffApi(request), [request])
  const [name, setName] = useState('')
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [reveal, setReveal] = useState(false)
  const [errors, setErrors] = useState<{ name?: string; email?: string; password?: string; form?: string }>({})
  const [busy, setBusy] = useState(false)
  // Credencial recém-criada, mostrada UMA vez para o prestador entregar ao atendente. Só em memória desta tela: nunca em
  // localStorage, sessionStorage nem log, e some ao limpar, ao sair da tela ou ao sair da conta.
  const [created, setCreated] = useState<{ name: string; email: string; password: string } | null>(null)
  const [copyMessage, setCopyMessage] = useState<string | null>(null)
  const once = useSingleFlight()
  const focusFirstInvalid = useFocusFirstInvalid()

  async function copy(text: string) {
    try {
      await navigator.clipboard.writeText(text)
      setCopyMessage('Senha copiada.')
    } catch {
      setCopyMessage('Não foi possível copiar automaticamente: selecione a senha acima e copie.')
    }
  }

  async function submit(event: FormEvent) {
    event.preventDefault()
    await once(async () => {
      const found = {
        name: name.trim() ? undefined : 'Informe o nome do atendente.',
        email: /^\S+@\S+$/.test(email.trim()) ? undefined : 'Informe um e-mail válido.',
        password: validateNewPassword(password) ?? undefined,
      }
      if (found.name || found.email || found.password) {
        setErrors(found)
        focusFirstInvalid()
        return
      }
      setErrors({})
      setNotice(null)
      setCreated(null)
      setCopyMessage(null)
      setBusy(true)
      try {
        await api.createAccount({ name: name.trim(), email: email.trim(), initialPassword: password })
        // Só depois do SUCESSO a senha enviada passa a ser exibida para entrega; falha nunca a apresenta como "conta criada".
        setCreated({ name: name.trim(), email: email.trim(), password })
        setName('')
        setEmail('')
        setPassword('')
        setReveal(false)
        await onCreated()
      } catch (e) {
        setErrors({ form: describeError(e) })
        focusFirstInvalid()
      } finally {
        setBusy(false)
      }
    })
  }

  const edit = (key: 'name' | 'email' | 'password', set: (value: string) => void) => (e: { target: { value: string } }) => {
    set(e.target.value)
    setErrors((x) => ({ ...x, [key]: undefined }))
  }

  return (
    <>
    {created && (
      <div role="status" className="notice">
        <p>
          Conta de <strong>{created.name}</strong> ({created.email}) criada. Entregue esta senha provisória ao atendente por um canal seguro: ela só vale para o primeiro acesso e <strong>não será mostrada de novo</strong>.
        </p>
        <label htmlFor="created-password">Senha provisória</label>
        <input id="created-password" readOnly value={created.password} onFocus={(e) => e.currentTarget.select()} />
        <p className="actions">
          <button type="button" className="button secondary" onClick={() => copy(created.password)}>
            Copiar senha
          </button>
          <button
            type="button"
            className="button"
            onClick={() => {
              setCreated(null)
              setCopyMessage(null)
            }}
          >
            Já entreguei, limpar da tela
          </button>
        </p>
        <p role="status" className="hint" hidden={!copyMessage}>
          {copyMessage}
        </p>
      </div>
    )}
    <form onSubmit={submit} noValidate>
      <Field label="Nome" name="staff-name" disabled={busy} value={name} error={errors.name} onChange={edit('name', setName)} />
      <Field label="E-mail" name="staff-email" type="email" autoComplete="off" disabled={busy} value={email} error={errors.email} onChange={edit('email', setEmail)} />
      <Field
        label="Senha provisória"
        name="staff-password"
        type={reveal ? 'text' : 'password'}
        autoComplete="new-password"
        disabled={busy}
        hint="Pelo menos 10 caracteres. O atendente será obrigado a trocá-la no primeiro acesso."
        value={password}
        error={errors.password}
        onChange={edit('password', setPassword)}
      />
      <p>
        <button
          type="button"
          className="button secondary"
          disabled={busy}
          onClick={() => {
            setPassword(generatePassword())
            setReveal(true) // a senha gerada fica visível para você copiar e entregar
            setErrors((x) => ({ ...x, password: undefined }))
          }}
        >
          Gerar senha
        </button>
      </p>
      <p role="alert" className="error" hidden={!errors.form}>
        {errors.form}
      </p>
      <button type="submit" className="button" disabled={busy}>
        {busy ? 'Criando…' : 'Criar conta'}
      </button>
    </form>
    </>
  )
}

function LinkExisting({ onLinked, setNotice }: { onLinked: () => Promise<void>; setNotice: Notice }) {
  const { request } = useAuth()
  const api = useMemo(() => staffApi(request), [request])
  const [email, setEmail] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const once = useSingleFlight()

  async function submit(event: FormEvent) {
    event.preventDefault()
    await once(async () => {
      if (!/^\S+@\S+$/.test(email.trim())) {
        setError('Informe um e-mail válido.')
        return
      }
      setError(null)
      setNotice(null)
      setBusy(true)
      try {
        await api.link(email.trim())
        setEmail('')
        setNotice('Atendente vinculado.')
        await onLinked()
      } catch (e) {
        setError(describeError(e))
      } finally {
        setBusy(false)
      }
    })
  }

  return (
    <form onSubmit={submit} noValidate>
      <Field
        label="E-mail do atendente"
        name="link-email"
        type="email"
        autoComplete="off"
        disabled={busy}
        hint="Só atendentes com conta ativa e que ainda não estão em outro prestador."
        value={email}
        error={error}
        onChange={(e) => {
          setEmail(e.target.value)
          setError(null)
        }}
      />
      <button type="submit" className="button" disabled={busy}>
        {busy ? 'Vinculando…' : 'Vincular'}
      </button>
    </form>
  )
}

type Pending = 'unlink' | 'deactivate' | null

function MemberRow({ member, onChanged, onRemoved, setNotice }: { member: StaffMember; onChanged: () => Promise<void>; onRemoved: () => void; setNotice: Notice }) {
  const { request } = useAuth()
  const api = useMemo(() => staffApi(request), [request])
  const [confirming, setConfirming] = useState<Pending>(null)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const once = useSingleFlight()

  async function run(action: Exclude<Pending, null>) {
    await once(async () => {
      setError(null)
      setNotice(null)
      setBusy(true)
      try {
        await (action === 'unlink' ? api.unlink(member.userId) : api.deactivate(member.userId))
        onRemoved() // some da lista agora; o GET seguinte só confirma
        setNotice(action === 'unlink' ? `${member.name} foi desvinculado da equipe.` : `A conta de ${member.name} foi desativada e saiu da equipe.`)
        await onChanged()
      } catch (e) {
        setError(describeError(e))
        setConfirming(null)
      } finally {
        setBusy(false)
      }
    })
  }

  return (
    <li className="row">
      <span>
        <strong>{member.name}</strong> · {member.email}
      </span>
      <span className="actions">
        {confirming === null ? (
          <>
            <button type="button" className="button secondary" disabled={busy} onClick={() => setConfirming('unlink')}>
              Desvincular
            </button>
            <button type="button" className="button secondary" disabled={busy} onClick={() => setConfirming('deactivate')}>
              Desativar conta
            </button>
          </>
        ) : (
          <>
            <span>{confirming === 'unlink' ? `Desvincular ${member.name}? A conta continua existindo.` : `Desativar a conta de ${member.name}? Ela perde o acesso e só um administrador reativa.`}</span>
            <button type="button" className="button" disabled={busy} onClick={() => run(confirming)}>
              {busy ? 'Aguarde…' : 'Confirmar'}
            </button>
            <button type="button" className="button secondary" disabled={busy} onClick={() => setConfirming(null)}>
              Voltar
            </button>
          </>
        )}
      </span>
      {error && (
        <p role="alert" className="error">
          {error}
        </p>
      )}
    </li>
  )
}
