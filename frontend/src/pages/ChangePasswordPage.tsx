import { useState, type FormEvent } from 'react'
import { useNavigate } from 'react-router-dom'
import { ApiError } from '../api/client'
import { describeError } from '../api/messages'
import { validateNewPassword } from '../auth/passwordPolicy'
import { useAuth } from '../auth/session'
import { Field } from './Field'
import { useSingleFlight } from './useSingleFlight'
import { useFocusFirstInvalid } from './useFocusFirstInvalid'

export function ChangePasswordPage() {
  const { user, changePassword } = useAuth()
  const navigate = useNavigate()
  const forced = user?.mustChangePassword ?? false
  const [current, setCurrent] = useState('')
  const [next, setNext] = useState('')
  const [confirm, setConfirm] = useState('')
  const [errors, setErrors] = useState<{ current?: string; next?: string; confirm?: string; form?: string }>({})
  const [busy, setBusy] = useState(false)
  const once = useSingleFlight()
  const focusFirstInvalid = useFocusFirstInvalid()

  async function submit(event: FormEvent) {
    event.preventDefault()
    await once(async () => {
    const found = {
      current: current ? undefined : 'Informe a senha atual.',
      next: validateNewPassword(next, current) ?? undefined,
      confirm: next === confirm ? undefined : 'A confirmação não é igual à nova senha.',
    }
    if (found.current || found.next || found.confirm) {
      setErrors(found)
      focusFirstInvalid()
      return
    }
    setErrors({})
    setBusy(true)
    try {
      await changePassword(current, next)
      navigate('/', { replace: true })
    } catch (e) {
      if (e instanceof ApiError && e.reason === 'INVALID_CURRENT_PASSWORD') setErrors({ current: describeError(e) })
      else if (e instanceof ApiError && e.status === 400 && e.fieldErrors.password) setErrors({ next: e.fieldErrors.password })
      else setErrors({ form: describeError(e) })
      focusFirstInvalid()
      setBusy(false)
    }
    })
  }

  return (
    <section className="card narrow">
      <h1>{forced ? 'Defina a sua senha' : 'Trocar senha'}</h1>
      {forced && (
        <p role="status" className="notice">
          Sua conta foi criada com uma senha provisória. Escolha uma senha só sua para continuar.
        </p>
      )}
      <form onSubmit={submit} noValidate>
        <Field label="Senha atual" name="current" type="password" autoComplete="current-password" value={current} error={errors.current} onChange={(e) => { setCurrent(e.target.value); setErrors((x) => ({ ...x, current: undefined })) }} />
        <Field
          label="Nova senha"
          name="next"
          type="password"
          autoComplete="new-password"
          hint="Pelo menos 10 caracteres, diferente da atual."
          value={next}
          error={errors.next}
          onChange={(e) => {
            setNext(e.target.value)
            setErrors((x) => ({ ...x, next: undefined }))
          }}
        />
        <Field label="Repita a nova senha" name="confirm" type="password" autoComplete="new-password" value={confirm} error={errors.confirm} onChange={(e) => { setConfirm(e.target.value); setErrors((x) => ({ ...x, confirm: undefined })) }} />
        <p role="alert" className="error" hidden={!errors.form}>
          {errors.form}
        </p>
        <button type="submit" className="button" disabled={busy}>
          {busy ? 'Salvando…' : 'Salvar nova senha'}
        </button>
      </form>
      {!forced && <p className="aside">Ao trocar a senha, você sai dos outros aparelhos.</p>}
    </section>
  )
}
