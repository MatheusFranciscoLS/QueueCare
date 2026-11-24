import { useState, type FormEvent } from 'react'
import { Link } from 'react-router-dom'
import { ApiError } from '../api/client'
import { describeError } from '../api/messages'
import { formatPhone, validatePhone } from '../api/phone'
import { ROLE_LABEL } from '../auth/roles'
import { isDemoUser } from '../auth/demo'
import { useAuth } from '../auth/session'
import { Field } from './Field'
import { useFocusFirstInvalid } from './useFocusFirstInvalid'
import { useSingleFlight } from './useSingleFlight'
import { MyDataSection } from './customer/MyDataSection'

/** Meu perfil: quem sou e o WhatsApp de contato. É por ele que o estúdio avisa quando o pet fica pronto (ADR 0019). */
export function ProfilePage() {
  const { user, updatePhone } = useAuth()
  const [phone, setPhone] = useState(formatPhone(user?.phone))
  const [error, setError] = useState<string | undefined>()
  const [saved, setSaved] = useState(false)
  const [busy, setBusy] = useState(false)
  const once = useSingleFlight()
  const focusFirstInvalid = useFocusFirstInvalid()
  if (!user) return null
  const needsPhone = user.role === 'CUSTOMER'

  async function submit(event: FormEvent) {
    event.preventDefault()
    await once(async () => {
      setSaved(false)
      const found = needsPhone || phone.trim() ? validatePhone(phone) : undefined
      if (found) {
        setError(found)
        focusFirstInvalid()
        return
      }
      setError(undefined)
      setBusy(true)
      try {
        await updatePhone(phone)
        setPhone(formatPhone(phoneDigits(phone)))
        setSaved(true)
      } catch (e) {
        setError(e instanceof ApiError && e.status === 400 && e.fieldErrors.phone ? e.fieldErrors.phone : describeError(e))
        focusFirstInvalid()
      } finally {
        setBusy(false)
      }
    })
  }

  return (
    <section className="card narrow">
      <h1>Meu perfil</h1>
      <dl className="profile-facts">
        <div>
          <dt>Nome</dt>
          <dd>{user.name}</dd>
        </div>
        <div>
          <dt>E-mail</dt>
          <dd>{user.email}</dd>
        </div>
        <div>
          <dt>Perfil</dt>
          <dd>{ROLE_LABEL[user.role]}</dd>
        </div>
      </dl>
      <h2>Contato</h2>
      <form onSubmit={submit} noValidate>
        <Field
          label="WhatsApp"
          name="phone"
          type="tel"
          autoComplete="tel"
          inputMode="tel"
          hint={needsPhone ? 'O estúdio usa este número para avisar quando o seu pet ficar pronto.' : 'Opcional. Aparece só para quem tem agendamento com você.'}
          value={phone}
          error={error}
          onChange={(e) => {
            setPhone(e.target.value)
            setError(undefined)
            setSaved(false)
          }}
        />
        <p role="status" className="notice" hidden={!saved}>
          Contato salvo.
        </p>
        <button type="submit" className="button" disabled={busy}>
          {busy ? 'Salvando…' : 'Salvar contato'}
        </button>
      </form>
      {isDemoUser(user) ? (
        <p className="hint">Na demonstração a troca de senha fica desligada: a conta é compartilhada por todos os visitantes.</p>
      ) : (
        <p className="aside">
          <Link to="/trocar-senha">Trocar senha</Link>
        </p>
      )}
      {user.role === 'CUSTOMER' && <MyDataSection />}
    </section>
  )
}

/** Só os dígitos, como a API devolve; usado para reexibir o número formatado depois de salvar. */
function phoneDigits(typed: string): string {
  const digits = typed.replace(/\D/g, '')
  return digits.length === 10 || digits.length === 11 ? `55${digits}` : digits
}
