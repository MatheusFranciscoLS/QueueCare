import { useState, type FormEvent } from 'react'
import { Link, useLocation, useNavigate } from 'react-router-dom'
import { describeError } from '../api/messages'
import { safeDestination } from '../auth/destinations'
import { useAuth } from '../auth/session'
import { AuthLayout } from './AuthLayout'
import { Field } from './Field'
import { useFocusFirstInvalid } from './useFocusFirstInvalid'
import { useSingleFlight } from './useSingleFlight'

export function LoginPage() {
  const { login, expired } = useAuth()
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
    </section>
    </AuthLayout>
  )
}
