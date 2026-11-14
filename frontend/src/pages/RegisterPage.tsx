import { useState, type FormEvent } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { ApiError } from '../api/client'
import { describeError } from '../api/messages'
import { validateNewPassword } from '../auth/passwordPolicy'
import { useAuth } from '../auth/session'
import { Field } from './Field'
import { useSingleFlight } from './useSingleFlight'
import { useFocusFirstInvalid } from './useFocusFirstInvalid'

export function RegisterPage() {
  const { register } = useAuth()
  const navigate = useNavigate()
  const [name, setName] = useState('')
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [errors, setErrors] = useState<{ name?: string; email?: string; password?: string; form?: string }>({})
  const [busy, setBusy] = useState(false)
  const once = useSingleFlight()
  const focusFirstInvalid = useFocusFirstInvalid()

  async function submit(event: FormEvent) {
    event.preventDefault()
    await once(async () => {
    const found = {
      name: name.trim() ? undefined : 'Informe o seu nome.',
      email: /^\S+@\S+$/.test(email.trim()) ? undefined : 'Informe um e-mail válido.',
      password: validateNewPassword(password) ?? undefined,
    }
    if (found.name || found.email || found.password) {
      setErrors(found)
      focusFirstInvalid()
      return
    }
    setErrors({})
    setBusy(true)
    try {
      await register(name.trim(), email.trim(), password)
      navigate('/', { replace: true })
    } catch (e) {
      if (e instanceof ApiError && e.status === 409) setErrors({ email: 'Esse e-mail já está cadastrado.' })
      else if (e instanceof ApiError && e.status === 400 && e.fieldErrors.password) setErrors({ password: e.fieldErrors.password })
      else setErrors({ form: describeError(e) })
      focusFirstInvalid()
      setBusy(false)
    }
    })
  }

  return (
    <section className="card narrow">
      <h1>Criar conta de cliente</h1>
      <p className="lead">Escolha o serviço e o horário e acompanhe os seus agendamentos.</p>
      <form onSubmit={submit} noValidate>
        <Field label="Nome" name="name" autoComplete="name" value={name} error={errors.name} onChange={(e) => { setName(e.target.value); setErrors((x) => ({ ...x, name: undefined })) }} />
        <Field label="E-mail" name="email" type="email" autoComplete="username" value={email} error={errors.email} onChange={(e) => { setEmail(e.target.value); setErrors((x) => ({ ...x, email: undefined })) }} />
        <Field
          label="Senha"
          name="password"
          type="password"
          autoComplete="new-password"
          hint="Pelo menos 10 caracteres."
          value={password}
          error={errors.password}
          onChange={(e) => {
            setPassword(e.target.value)
            setErrors((x) => ({ ...x, password: undefined }))
          }}
        />
        <p role="alert" className="error" hidden={!errors.form}>
          {errors.form}
        </p>
        <button type="submit" className="button" disabled={busy}>
          {busy ? 'Criando…' : 'Criar conta'}
        </button>
      </form>
      <p className="aside">
        Já tem conta? <Link to="/entrar">Entrar</Link>
      </p>
    </section>
  )
}
