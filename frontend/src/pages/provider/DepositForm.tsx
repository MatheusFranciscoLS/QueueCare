import { useMemo, useState, type FormEvent } from 'react'
import { ApiError } from '../../api/client'
import { describeError } from '../../api/messages'
import { moneyInput, parseMoney } from '../../api/money'
import { providerApi, type Business, type DepositRule } from '../../api/provider'
import { useAuth } from '../../auth/session'
import { Field } from '../Field'
import { useSingleFlight } from '../useSingleFlight'

const RULES: { value: DepositRule; label: string }[] = [
  { value: 'NEVER', label: 'Nunca pedir sinal' },
  { value: 'NEW_CUSTOMERS', label: 'Pedir de quem ainda não foi atendido aqui' },
  { value: 'NEW_OR_REPEAT_NO_SHOW', label: 'Pedir de quem ainda não foi atendido aqui ou já faltou 2 vezes' },
]

/**
 * Sinal por Pix, manual (ADR 0023): chave, valor e QUANDO pedir. Começa desligado. O app só mostra a chave e o valor ao tutor e a equipe marca "recebido":
 * não há cobrança, multa nem integração com banco. O servidor decide a quem pedir (o tutor nunca vê a contagem de faltas).
 */
/** O aviso "salvo" mora no pai: depois de salvar a tela recarrega e este formulário é refeito com o que o SERVIDOR devolveu. */
export function DepositForm({ business, saved, setSaved, onSaved }: { business: Business; saved: boolean; setSaved: (value: boolean) => void; onSaved: () => Promise<void> }) {
  const { request } = useAuth()
  const api = useMemo(() => providerApi(request), [request])
  const [rule, setRule] = useState<DepositRule>(business.depositRule ?? 'NEVER')
  const [pixKey, setPixKey] = useState(business.pixKey ?? '')
  const [amount, setAmount] = useState(moneyInput(business.depositCents))
  const [errors, setErrors] = useState<{ key?: string; amount?: string; form?: string }>({})
  const [busy, setBusy] = useState(false)
  const once = useSingleFlight()

  async function submit(event: FormEvent) {
    event.preventDefault()
    await once(async () => {
      const cents = parseMoney(amount)
      const found: typeof errors = {}
      if (cents === undefined) found.amount = 'Informe o valor em reais, por exemplo 30 ou 29,90.'
      if (rule !== 'NEVER') {
        if (!pixKey.trim()) found.key = 'Informe a chave Pix para pedir sinal.'
        if (cents === null) found.amount = 'Informe o valor do sinal para pedir sinal.'
        if (cents === 0) found.amount = 'O sinal precisa ser maior que zero.'
      }
      if (found.key || found.amount) {
        setErrors(found)
        return
      }
      setErrors({})
      setSaved(false)
      setBusy(true)
      try {
        await api.updateDeposit({ pixKey: pixKey.trim(), depositCents: cents ?? null, depositRule: rule })
        setSaved(true)
        await onSaved()
      } catch (e) {
        if (e instanceof ApiError && e.status === 400 && (e.fieldErrors.pixKey || e.fieldErrors.depositCents)) setErrors({ key: e.fieldErrors.pixKey, amount: e.fieldErrors.depositCents })
        else setErrors({ form: describeError(e) })
      } finally {
        setBusy(false)
      }
    })
  }

  return (
    <section className="card deposit-form" aria-label="Sinal por Pix">
      <h2>Sinal por Pix</h2>
      <p className="hint">
        Para proteger a agenda sem afastar cliente novo: peça sinal só de quem faz sentido. O app mostra a chave e o valor ao tutor antes de ele confirmar, e você marca
        &quot;sinal recebido&quot; na Agenda. Não há cobrança nem multa pelo app.
      </p>
      <form onSubmit={submit} noValidate>
        <div className="field">
          <label htmlFor="deposit-rule">Quando pedir sinal</label>
          <select id="deposit-rule" disabled={busy} value={rule} onChange={(e) => { setRule(e.target.value as DepositRule); setSaved(false) }}>
            {RULES.map((r) => (
              <option key={r.value} value={r.value}>
                {r.label}
              </option>
            ))}
          </select>
        </div>
        <div className="field-row">
          <Field label="Chave Pix" name="pix-key" disabled={busy} hint="CPF, CNPJ, e-mail, telefone ou chave aleatória." value={pixKey} error={errors.key} onChange={(e) => { setPixKey(e.target.value); setSaved(false); setErrors((x) => ({ ...x, key: undefined })) }} />
          <Field label="Valor do sinal (R$)" name="deposit-amount" disabled={busy} inputMode="decimal" hint="O valor abate do preço no balcão." value={amount} error={errors.amount} onChange={(e) => { setAmount(e.target.value); setSaved(false); setErrors((x) => ({ ...x, amount: undefined })) }} />
        </div>
        <p role="alert" className="error" hidden={!errors.form}>
          {errors.form}
        </p>
        <p role="status" className="notice" hidden={!saved}>
          Configuração do sinal salva.
        </p>
        <button type="submit" className="button" disabled={busy}>
          {busy ? 'Salvando…' : 'Salvar sinal'}
        </button>
      </form>
    </section>
  )
}
