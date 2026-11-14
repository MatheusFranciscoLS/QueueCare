import { useMemo, useState, type FormEvent } from 'react'
import { Link } from 'react-router-dom'
import { adminApi, type CreatedProvider } from '../../api/admin'
import { ApiError } from '../../api/client'
import { describeError } from '../../api/messages'
import { DEFAULT_TIME_ZONE, timeZones } from '../../api/timezones'
import { generatePassword } from '../../auth/generatePassword'
import { validateNewPassword } from '../../auth/passwordPolicy'
import { useAuth } from '../../auth/session'
import { Field } from '../Field'
import { useFocusFirstInvalid } from '../useFocusFirstInvalid'
import { useSingleFlight } from '../useSingleFlight'
import { PasswordDelivery } from './PasswordDelivery'

const MAX_MINUTES = 7 * 24 * 60
type FieldName = 'name' | 'email' | 'password' | 'businessName' | 'notice' | 'deadline' | 'form'

/** Criar a conta de um prestador junto com o negócio dele (só ADMIN). A senha provisória é mostrada UMA vez, depois do sucesso. */
export function CreateProviderPage() {
  const { request } = useAuth()
  const api = useMemo(() => adminApi(request), [request])
  const [name, setName] = useState('')
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [reveal, setReveal] = useState(false)
  const [businessName, setBusinessName] = useState('')
  const [zone, setZone] = useState(DEFAULT_TIME_ZONE)
  const [notice, setNotice] = useState('0')
  const [deadline, setDeadline] = useState('0')
  const [errors, setErrors] = useState<Partial<Record<FieldName, string>>>({})
  const [busy, setBusy] = useState(false)
  // Credencial recém-criada, só em memória desta tela, mostrada uma vez.
  const [created, setCreated] = useState<{ provider: CreatedProvider; password: string } | null>(null)
  const once = useSingleFlight()
  const focusFirstInvalid = useFocusFirstInvalid()
  const zones = useMemo(() => timeZones(), [])

  const edit = (key: FieldName, set: (value: string) => void) => (e: { target: { value: string } }) => {
    set(e.target.value)
    setErrors((x) => ({ ...x, [key]: undefined }))
  }

  async function submit(event: FormEvent) {
    event.preventDefault()
    await once(async () => {
      const found: Partial<Record<FieldName, string>> = {}
      if (!name.trim()) found.name = 'Informe o nome do prestador.'
      else if (name.trim().length > 120) found.name = 'No máximo 120 caracteres.'
      if (!/^\S+@\S+$/.test(email.trim())) found.email = 'Informe um e-mail válido.'
      const invalidPassword = validateNewPassword(password)
      if (invalidPassword) found.password = invalidPassword
      if (!businessName.trim()) found.businessName = 'Informe o nome do negócio.'
      else if (businessName.trim().length > 160) found.businessName = 'No máximo 160 caracteres.'
      const noticeMinutes = Number(notice)
      const deadlineMinutes = Number(deadline)
      if (!Number.isInteger(noticeMinutes) || noticeMinutes < 0 || noticeMinutes > MAX_MINUTES) found.notice = `Informe um número de minutos entre 0 e ${MAX_MINUTES}.`
      if (!Number.isInteger(deadlineMinutes) || deadlineMinutes < 0 || deadlineMinutes > MAX_MINUTES) found.deadline = `Informe um número de minutos entre 0 e ${MAX_MINUTES}.`
      if (Object.keys(found).length > 0) {
        setErrors(found)
        focusFirstInvalid()
        return
      }
      setErrors({})
      setCreated(null)
      setBusy(true)
      const sent = password
      try {
        const provider = await api.createProvider({
          name: name.trim(),
          email: email.trim(),
          initialPassword: sent,
          businessName: businessName.trim(),
          timeZone: zone,
          minimumNoticeMinutes: noticeMinutes,
          cancellationDeadlineMinutes: deadlineMinutes,
        })
        // Só depois do SUCESSO a senha enviada passa a ser exibida; falha nunca a apresenta como "conta criada".
        setCreated({ provider, password: sent })
        setName('')
        setEmail('')
        setPassword('')
        setReveal(false)
        setBusinessName('')
        setZone(DEFAULT_TIME_ZONE)
        setNotice('0')
        setDeadline('0')
      } catch (e) {
        if (e instanceof ApiError && e.status === 400) {
          const f = e.fieldErrors
          const field: Partial<Record<FieldName, string>> = {
            name: f.name,
            email: f.email,
            password: f.initialPassword,
            businessName: f.businessName,
            notice: f.minimumNoticeMinutes,
            deadline: f.cancellationDeadlineMinutes,
          }
          const pointed = Object.values(field).some(Boolean)
          setErrors({ ...field, form: pointed ? undefined : (f.timeZone ?? describeError(e)) })
        } else setErrors({ form: describeError(e) })
        focusFirstInvalid()
      } finally {
        setBusy(false)
      }
    })
  }

  return (
    <section className="card">
      <h1>Novo prestador</h1>
      {created && (
        <PasswordDelivery password={created.password} onDone={() => setCreated(null)}>
          Conta de <strong>{created.provider.name}</strong> ({created.provider.email}) e negócio <strong>{created.provider.businessName}</strong> criados. Entregue esta senha provisória por um
          canal seguro: ela só vale para o primeiro acesso e <strong>não será mostrada de novo</strong>. Depois, veja a conta em <Link to="/admin/contas">Contas</Link>.
        </PasswordDelivery>
      )}
      <form onSubmit={submit} noValidate>
        <Field label="Nome do prestador" name="provider-name" disabled={busy} value={name} error={errors.name} onChange={edit('name', setName)} />
        <Field label="E-mail" name="provider-email" type="email" autoComplete="off" disabled={busy} value={email} error={errors.email} onChange={edit('email', setEmail)} />
        <Field
          label="Senha provisória"
          name="provider-password"
          type={reveal ? 'text' : 'password'}
          autoComplete="new-password"
          disabled={busy}
          hint="Pelo menos 10 caracteres. O prestador será obrigado a trocá-la no primeiro acesso."
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
        <Field label="Nome do negócio" name="provider-business" disabled={busy} value={businessName} error={errors.businessName} onChange={edit('businessName', setBusinessName)} />
        <div className="field">
          <label htmlFor="provider-zone">Fuso horário do negócio</label>
          <select id="provider-zone" disabled={busy} value={zone} onChange={(e) => setZone(e.target.value)}>
            {zones.map((z) => (
              <option key={z} value={z}>
                {z}
              </option>
            ))}
          </select>
        </div>
        <Field
          label="Antecedência mínima (minutos)"
          name="provider-notice"
          type="number"
          inputMode="numeric"
          min={0}
          disabled={busy}
          hint="O cliente só agenda com pelo menos este tempo de antecedência."
          value={notice}
          error={errors.notice}
          onChange={edit('notice', setNotice)}
        />
        <Field
          label="Prazo para o cliente cancelar (minutos antes)"
          name="provider-deadline"
          type="number"
          inputMode="numeric"
          min={0}
          disabled={busy}
          hint="O cliente só cancela até este tempo antes do horário. A equipe cancela a qualquer momento."
          value={deadline}
          error={errors.deadline}
          onChange={edit('deadline', setDeadline)}
        />
        <p role="alert" className="error" hidden={!errors.form}>
          {errors.form}
        </p>
        <button type="submit" className="button" disabled={busy}>
          {busy ? 'Criando…' : 'Criar prestador'}
        </button>
      </form>
    </section>
  )
}
