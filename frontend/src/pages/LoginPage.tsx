import { useState, type FormEvent } from 'react'
import { Link, useLocation, useNavigate } from 'react-router-dom'
import { describeError } from '../api/messages'
import { useAuth } from '../auth/session'
import { Field } from './Field'

export function LoginPage() {
  const { login, expired } = useAuth()
  const navigate = useNavigate()
  const location = useLocation()
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  async function submit(event: FormEvent) {
    event.preventDefault()
    setError(null)
    setBusy(true)
    try {
      await login(email.trim(), password)
      const from = (location.state as { from?: string } | null)?.from
      navigate(from && from !== '/entrar' ? from : '/', { replace: true })
    } catch (e) {
      setError(describeError(e))
      setBusy(false)
    }
  }

  return (
    <section className="card narrow">
      <h1>Entrar</h1>
      {expired && (
        <p role="status" className="notice">
          Sua sessão terminou. Entre novamente para continuar.
        </p>
      )}
      <form onSubmit={submit} noValidate>
        <Field label="E-mail" name="email" type="email" autoComplete="username" required value={email} onChange={(e) => setEmail(e.target.value)} />
        <Field label="Senha" name="password" type="password" autoComplete="current-password" required value={password} onChange={(e) => setPassword(e.target.value)} />
        <p role="alert" className="error" hidden={!error}>
          {error}
        </p>
        <button type="submit" className="button" disabled={busy || !email.trim() || !password}>
          {busy ? 'Entrando…' : 'Entrar'}
        </button>
      </form>
      <p className="aside">
        Ainda não tem conta? <Link to="/cadastro">Cadastre-se como cliente</Link>
      </p>
    </section>
  )
}
