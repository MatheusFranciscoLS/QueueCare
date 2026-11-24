import { useState, type FormEvent } from 'react'
import { Link, useLocation, useNavigate } from 'react-router-dom'
import { describeError } from '../api/messages'
import { safeDestination } from '../auth/destinations'
import { useAuth, type DemoProfile } from '../auth/session'
import { AuthLayout } from './AuthLayout'
import { Field } from './Field'
import { useFocusFirstInvalid } from './useFocusFirstInvalid'
import { useSingleFlight } from './useSingleFlight'

/** A demonstração pública (ADR 0041) aparece só no site publicado, que define VITE_DEMO=true no build (frontend/.env.production). */
const demoEnabled = () => import.meta.env.VITE_DEMO === 'true'
const DEMO_PROFILES: [DemoProfile, string][] = [
  ['OWNER', 'Entrar como dona do estúdio'],
  ['ATTENDANT', 'Entrar como atendente'],
  ['CUSTOMER', 'Entrar como tutora (cliente)'],
]

export function LoginPage() {
  const { login, loginDemo, expired } = useAuth()
  const navigate = useNavigate()
  const location = useLocation()
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState<string | null>(null)
  // Campo vazio é explicado AO ENVIAR (o botão não fica apagado: um botão principal que parece desligado parece quebrado, revisão de 21/11/2026).
  const [fieldErrors, setFieldErrors] = useState<{ email?: string; password?: string }>({})
  const focusFirstInvalid = useFocusFirstInvalid()
  const [busy, setBusy] = useState(false)
  const once = useSingleFlight()

  async function submit(event: FormEvent) {
    event.preventDefault()
    await once(async () => {
    const missing = { email: email.trim() ? undefined : 'Informe o seu e-mail.', password: password ? undefined : 'Informe a sua senha.' }
    if (missing.email || missing.password) {
      setFieldErrors(missing)
      setError(null)
      focusFirstInvalid()
      return
    }
    setFieldErrors({})
    setError(null)
    setBusy(true)
    try {
      await login(email.trim(), password)
      navigate(safeDestination((location.state as { from?: unknown } | null)?.from), { replace: true })
    } catch (e) {
      setError(describeError(e))
      setBusy(false)
    }
    })
  }

  async function enterDemo(profile: DemoProfile) {
    await once(async () => {
      setError(null)
      setBusy(true)
      try {
        await loginDemo(profile)
        navigate('/', { replace: true })
      } catch (e) {
        setError(describeError(e))
        setBusy(false)
      }
    })
  }

  return (
    <AuthLayout>
    <section className="card narrow">
      <h1>Entrar</h1>
      <p className="lead">Entre para ver a agenda e a fila de hoje.</p>
      {expired && (
        <p role="status" className="notice">
          Sua sessão terminou. Entre novamente para continuar.
        </p>
      )}
      <form onSubmit={submit} noValidate>
        <Field label="E-mail" name="email" type="email" autoComplete="username" required value={email} error={fieldErrors.email} onChange={(e) => { setEmail(e.target.value); setFieldErrors((x) => ({ ...x, email: undefined })) }} />
        <Field label="Senha" name="password" type="password" reveal autoComplete="current-password" required value={password} error={fieldErrors.password} onChange={(e) => { setPassword(e.target.value); setFieldErrors((x) => ({ ...x, password: undefined })) }} />
        <p role="alert" className="error" hidden={!error}>
          {error}
        </p>
        <button type="submit" className="button" disabled={busy}>
          {busy ? 'Entrando…' : 'Entrar'}
        </button>
      </form>
      <p className="aside">
        Ainda não tem conta? <Link to="/cadastro">Cadastre-se como cliente</Link>
      </p>
      {demoEnabled() && (
        <section className="demo-entry" aria-labelledby="demo-title">
          <h2 id="demo-title">Conhecer sem cadastro</h2>
          <p className="aside">Entre com um clique num estúdio fictício, com agenda, fila e clientes de exemplo. Nada aqui é real.</p>
          <div className="demo-entry__buttons">
            {DEMO_PROFILES.map(([profile, label]) => (
              <button key={profile} type="button" className="button secondary" disabled={busy} onClick={() => enterDemo(profile)}>
                {label}
              </button>
            ))}
          </div>
        </section>
      )}
      <p className="aside">
        <Link to="/privacidade">Política de Privacidade</Link>
      </p>
    </section>
    </AuthLayout>
  )
}
