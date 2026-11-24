import { useMemo, useState, type FormEvent } from 'react'
import { ApiError } from '../../api/client'
import { customersApi, type CounterCustomer, type StudioCustomer } from '../../api/customers'
import { describeError } from '../../api/messages'
import { formatPhone, validatePhone } from '../../api/phone'
import { useAuth } from '../../auth/session'
import { Field } from '../Field'
import { useSingleFlight } from '../useSingleFlight'

type Errors = Partial<Record<'name' | 'phone' | 'email' | 'form', string>>

const EMAIL_SHAPE = /^[^@\s]+@[^@\s]+\.[^@\s]+$/

/**
 * Cadastro de um tutor que ligou ou chamou no WhatsApp (ADR 0033). Antes de criar, procura quem já existe com o mesmo telefone (a duplicidade é o erro mais comum do
 * balcão) e deixa a equipe escolher entre usar o cadastro que já existe e cadastrar mesmo assim (família divide número). Quem é cadastrado aqui NÃO ganha acesso ao
 * app: sem e-mail não há como entrar, e dar acesso é uma decisão separada.
 */
export function NewCustomerForm({ onCreated, onCancel, onUseExisting }: { onCreated: (customer: CounterCustomer) => void; onCancel: () => void; onUseExisting: (customer: StudioCustomer) => void }) {
  const { request } = useAuth()
  const api = useMemo(() => customersApi(request), [request])
  const [name, setName] = useState('')
  const [phone, setPhone] = useState('')
  const [email, setEmail] = useState('')
  const [errors, setErrors] = useState<Errors>({})
  const [similar, setSimilar] = useState<StudioCustomer[] | null>(null)
  const [busy, setBusy] = useState(false)
  const once = useSingleFlight()

  function validate(): Errors {
    const found: Errors = {}
    if (!name.trim()) found.name = 'Informe o nome do tutor.'
    else if ([...name.trim()].length > 120) found.name = 'No máximo 120 caracteres.'
    const phoneProblem = validatePhone(phone)
    if (phoneProblem) found.phone = phone.trim() ? phoneProblem : 'Informe o WhatsApp do tutor, com DDD.'
    if (email.trim() && !EMAIL_SHAPE.test(email.trim())) found.email = 'Esse e-mail não parece válido. Deixe em branco se o tutor não tiver.'
    return found
  }

  async function create() {
    setBusy(true)
    try {
      onCreated(await api.createCustomer({ name, phone, email }))
    } catch (e) {
      if (e instanceof ApiError && e.status === 400 && Object.keys(e.fieldErrors).length > 0) {
        setErrors({ name: e.fieldErrors.name, phone: e.fieldErrors.phone, email: e.fieldErrors.email })
      } else if (e instanceof ApiError && e.status === 409) {
        setErrors({ email: 'Esse e-mail já tem cadastro no app: o tutor pode agendar sozinho, ou cadastre sem e-mail.' })
      } else {
        setErrors({ form: describeError(e) })
      }
    } finally {
      setBusy(false)
    }
  }

  async function submit(event: FormEvent) {
    event.preventDefault()
    await once(async () => {
      const found = validate()
      setErrors(found)
      if (Object.keys(found).length > 0) return
      if (similar === null) {
        // Primeiro envio: procura pelo telefone (o que a equipe tem em mãos). Falha na busca não impede o cadastro.
        setBusy(true)
        try {
          const digits = phone.replace(/\D/g, '')
          const results = await api.search(digits.slice(-9))
          if (results.length > 0) {
            setSimilar(results)
            return
          }
        } catch {
          // segue para o cadastro
        } finally {
          setBusy(false)
        }
      }
      await create()
    })
  }

  return (
    <form className="card customers__new" onSubmit={submit} noValidate aria-labelledby="new-customer-title">
      <h2 id="new-customer-title">Novo cliente</h2>
      <p className="hint">Para quem ligou ou chamou no WhatsApp e ainda não tem conta. O tutor não ganha acesso ao app: você agenda por ele.</p>
      <Field
        label="Nome do tutor"
        name="new-customer-name"
        autoComplete="off"
        value={name}
        maxLength={120}
        error={errors.name}
        onChange={(e) => {
          setName(e.target.value)
          setErrors((c) => ({ ...c, name: undefined, form: undefined }))
          setSimilar(null)
        }}
      />
      <Field
        label="WhatsApp do tutor"
        name="new-customer-phone"
        type="tel"
        inputMode="tel"
        autoComplete="off"
        value={phone}
        hint="Com DDD. É por onde o estúdio fala com ele."
        error={errors.phone}
        onChange={(e) => {
          setPhone(e.target.value)
          setErrors((c) => ({ ...c, phone: undefined, form: undefined }))
          setSimilar(null)
        }}
      />
      <Field
        label="E-mail (opcional)"
        name="new-customer-email"
        type="email"
        autoComplete="off"
        value={email}
        maxLength={254}
        hint="Só preencha se o tutor for entrar no app depois."
        error={errors.email}
        onChange={(e) => {
          setEmail(e.target.value)
          setErrors((c) => ({ ...c, email: undefined, form: undefined }))
        }}
      />

      {similar && similar.length > 0 && (
        <div role="alert" className="notice customers__similar">
          <p>
            <strong>Já existe cadastro com este telefone.</strong> Use o que já existe para não duplicar:
          </p>
          <ul className="rows">
            {similar.map((c) => (
              <li key={c.id} className="row">
                <span>
                  <strong>{c.name}</strong>
                  {c.phone ? ` · ${formatPhone(c.phone)}` : ''}
                  {c.pets.length > 0 ? ` · Pets: ${c.pets.join(', ')}` : ''}
                </span>
                <button type="button" className="button secondary" onClick={() => onUseExisting(c)}>
                  Usar este cadastro
                  <span className="pp-sr"> ({c.name})</span>
                </button>
              </li>
            ))}
          </ul>
          <p className="hint">Se for outra pessoa (por exemplo, da mesma família), cadastre mesmo assim.</p>
        </div>
      )}

      {errors.form && (
        <p role="alert" className="error">
          {errors.form}
        </p>
      )}
      <span className="actions">
        <button type="submit" className={similar ? 'button secondary' : 'button'} disabled={busy}>
          {busy ? 'Cadastrando…' : similar ? 'Cadastrar mesmo assim' : 'Cadastrar cliente'}
        </button>
        <button type="button" className="button secondary" disabled={busy} onClick={onCancel}>
          Cancelar
        </button>
      </span>
    </form>
  )
}
