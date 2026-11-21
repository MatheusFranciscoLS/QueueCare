import { useCallback, useMemo, useState, type FormEvent } from 'react'
import { ApiError } from '../../api/client'
import { isNotConfigured } from '../../api/errors'
import { describeError } from '../../api/messages'
import { formatPhone, validatePhone } from '../../api/phone'
import { minutesText } from '../../api/policy'
import { providerApi, type Business, type BusinessInput } from '../../api/provider'
import { DEFAULT_TIME_ZONE, timeZones } from '../../api/timezones'
import { useAuth } from '../../auth/session'
import { DepositForm } from './DepositForm'
import { Field } from '../Field'
import { useFocusFirstInvalid } from '../useFocusFirstInvalid'
import { RefreshWarning } from '../RefreshWarning'
import { useLoad } from '../useLoad'
import { useSingleFlight } from '../useSingleFlight'
import { Loading } from '../Loading'

const MAX_MINUTES = 7 * 24 * 60
/** Prazo sugerido para um negócio novo: 12 h. Zero deixaria o estúdio sem proteção nenhuma contra cancelamento em cima da hora (ADR 0021). */
const DEFAULT_DEADLINE_MINUTES = 720

export function BusinessPage() {
  const { request } = useAuth()
  const api = useMemo(() => providerApi(request), [request])
  const load = useCallback(() => api.getBusiness(), [api])
  const { data, error, loading, reload, version } = useLoad<Business>(load)
  // O aviso "salvo" fica aqui porque o formulário é refeito a cada carregamento bem-sucedido.
  const [saved, setSaved] = useState(false)
  const [depositSaved, setDepositSaved] = useState(false)

  if (loading && !data) return <Loading />
  if (error && !data && !isNotConfigured(error)) return <p role="alert" className="error">{describeError(error)}</p>
  return (
    <>
      {error !== undefined && data && <RefreshWarning onRetry={reload} />}
      <BusinessForm key={version} business={data} saved={saved} setSaved={setSaved} onSaved={reload} />
      {data && <DepositForm key={'deposit-' + version} business={data} saved={depositSaved} setSaved={setDepositSaved} onSaved={reload} />}
    </>
  )
}

function BusinessForm({ business, saved, setSaved, onSaved }: { business?: Business; saved: boolean; setSaved: (value: boolean) => void; onSaved: () => Promise<void> }) {
  const { request } = useAuth()
  const api = useMemo(() => providerApi(request), [request])
  const creating = !business
  const [name, setName] = useState(business?.businessName ?? '')
  const [zone, setZone] = useState(business?.timeZone ?? DEFAULT_TIME_ZONE)
  const [notice, setNotice] = useState(String(business?.minimumNoticeMinutes ?? 0))
  const [deadline, setDeadline] = useState(String(business?.cancellationDeadlineMinutes ?? DEFAULT_DEADLINE_MINUTES))
  const [phone, setPhone] = useState(formatPhone(business?.phone))
  const [errors, setErrors] = useState<Partial<Record<'name' | 'notice' | 'deadline' | 'phone' | 'form', string>>>({})
  const [busy, setBusy] = useState(false)
  const once = useSingleFlight()
  const focusFirstInvalid = useFocusFirstInvalid()
  const zones = useMemo(() => timeZones(business?.timeZone), [business?.timeZone])

  const clear = (key: 'name' | 'notice' | 'deadline' | 'phone') => {
    setSaved(false)
    setErrors((x) => ({ ...x, [key]: undefined }))
  }

  async function submit(event: FormEvent) {
    event.preventDefault()
    await once(async () => {
      const found: typeof errors = {}
      if (!name.trim()) found.name = 'Informe o nome do negócio.'
      else if (name.trim().length > 160) found.name = 'No máximo 160 caracteres.'
      const noticeMinutes = Number(notice)
      const deadlineMinutes = Number(deadline)
      if (!Number.isInteger(noticeMinutes) || noticeMinutes < 0 || noticeMinutes > MAX_MINUTES) found.notice = `Informe um número de minutos entre 0 e ${MAX_MINUTES}.`
      if (!Number.isInteger(deadlineMinutes) || deadlineMinutes < 0 || deadlineMinutes > MAX_MINUTES) found.deadline = `Informe um número de minutos entre 0 e ${MAX_MINUTES}.`
      if (phone.trim()) found.phone = validatePhone(phone)
      if (found.name || found.notice || found.deadline || found.phone) {
        setErrors(found)
        focusFirstInvalid()
        return
      }
      setErrors({})
      setSaved(false)
      setBusy(true)
      const input: BusinessInput = { businessName: name.trim(), timeZone: zone, minimumNoticeMinutes: noticeMinutes, cancellationDeadlineMinutes: deadlineMinutes, phone: phone.trim() }
      try {
        await (creating ? api.createBusiness(input) : api.updateBusiness(input))
        setSaved(true)
        await onSaved() // o formulário continua bloqueado até o GET posterior terminar e passa a mostrar o que o SERVIDOR devolveu
      } catch (e) {
        if (e instanceof ApiError && e.status === 400) {
          const field = { name: e.fieldErrors.businessName, notice: e.fieldErrors.minimumNoticeMinutes, deadline: e.fieldErrors.cancellationDeadlineMinutes, phone: e.fieldErrors.phone }
          // Erro de campo aparece no campo; o aviso geral só quando não há campo a apontar (por exemplo, o fuso).
          setErrors({ ...field, form: field.name || field.notice || field.deadline || field.phone ? undefined : (e.fieldErrors.timeZone ?? describeError(e)) })
        } else setErrors({ form: describeError(e) })
        focusFirstInvalid()
      } finally {
        setBusy(false)
      }
    })
  }

  return (
    <section className="card">
      <h1>{creating ? 'Cadastre o seu negócio' : 'Meu negócio'}</h1>
      {creating && <p className="notice">Antes de oferecer serviços e horários, diga como o seu negócio funciona.</p>}
      <form onSubmit={submit} noValidate>
        <Field label="Nome do negócio" name="name" disabled={busy} value={name} error={errors.name} onChange={(e) => { setName(e.target.value); clear('name') }} />
        <div className="field">
          <label htmlFor="zone">Fuso horário</label>
          <select id="zone" disabled={busy} value={zone} onChange={(e) => { setZone(e.target.value); setSaved(false) }}>
            {zones.map((z) => (
              <option key={z} value={z}>
                {z}
              </option>
            ))}
          </select>
          <p className="hint">Os horários do expediente valem neste fuso.</p>
        </div>
        <div className="field-row">
        <Field
          label="Antecedência mínima (minutos)"
          name="notice"
          disabled={busy}
          type="number"
          inputMode="numeric"
          min={0}
          hint="O cliente só agenda com pelo menos este tempo de antecedência. Exemplo: 60 é uma hora."
          value={notice}
          error={errors.notice}
          onChange={(e) => { setNotice(e.target.value); clear('notice') }}
        />
        <Field
          label="Prazo para o cliente cancelar (minutos antes)"
          name="deadline"
          disabled={busy}
          type="number"
          inputMode="numeric"
          min={0}
          hint={`O cliente cancela ou remarca de graça até este tempo antes do horário (${minutesText(Number(deadline))}); depois, fala com o estúdio. A equipe cancela a qualquer momento.`}
          value={deadline}
          error={errors.deadline}
          onChange={(e) => { setDeadline(e.target.value); clear('deadline') }}
        />
        </div>
        <Field
          label="WhatsApp do estúdio (opcional)"
          name="studio-phone"
          disabled={busy}
          type="tel"
          inputMode="tel"
          autoComplete="off"
          hint="O cliente usa este número para falar com você quando o prazo de cancelamento já passou. Exemplo: (11) 91234-5678."
          value={phone}
          error={errors.phone}
          onChange={(e) => { setPhone(e.target.value); clear('phone') }}
        />
        <p role="alert" className="error" hidden={!errors.form}>
          {errors.form}
        </p>
        <p role="status" className="notice" hidden={!saved}>
          Salvo.
        </p>
        <button type="submit" className="button" disabled={busy}>
          {busy ? 'Salvando…' : creating ? 'Cadastrar negócio' : 'Salvar'}
        </button>
      </form>
    </section>
  )
}
