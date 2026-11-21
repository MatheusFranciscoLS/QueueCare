import { useMemo, useState } from 'react'
import { Link } from 'react-router-dom'
import { describeError } from '../../api/messages'
import { downloadJson, privacyApi } from '../../api/privacy'
import { useAuth } from '../../auth/session'
import { Field } from '../Field'
import { useSingleFlight } from '../useSingleFlight'

/**
 * Meus dados (ADR 0027, LGPD mínima): o tutor baixa tudo o que o app guarda dele e pode excluir a conta. Excluir é irreversível (a conta é anonimizada e os horários
 * ainda marcados são cancelados), por isso pede a senha e explica o que acontece antes.
 */
export function MyDataSection() {
  const { request, logout } = useAuth()
  const api = useMemo(() => privacyApi(request), [request])
  const [exported, setExported] = useState(false)
  const [step, setStep] = useState<'idle' | 'confirm'>('idle')
  const [password, setPassword] = useState('')
  const [problem, setProblem] = useState<string | null>(null)
  const [fieldError, setFieldError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const once = useSingleFlight()

  async function download() {
    await once(async () => {
      setProblem(null)
      setExported(false)
      setBusy(true)
      try {
        downloadJson('meus-dados.json', await api.exportData())
        setExported(true)
      } catch (e) {
        setProblem(describeError(e))
      } finally {
        setBusy(false)
      }
    })
  }

  async function remove() {
    await once(async () => {
      setProblem(null)
      if (!password) {
        setFieldError('Informe a sua senha para confirmar.')
        return
      }
      setFieldError(null)
      setBusy(true)
      try {
        await api.deleteAccount(password)
        logout() // a conta já não existe: a sessão acaba e a pessoa volta para a tela de entrada
      } catch (e) {
        setProblem(describeError(e))
        setBusy(false)
      }
    })
  }

  return (
    <section className="card my-data" aria-label="Meus dados e privacidade">
      <h2>Meus dados e privacidade</h2>
      <p className="hint">Veja o que o app guarda de você, para que serve e como exercer seus direitos.</p>
      <p className="aside">
        <Link to="/privacidade">Política de Privacidade</Link>
      </p>

      <p>
        <button type="button" className="button secondary" disabled={busy} onClick={download}>
          Baixar meus dados
        </button>
      </p>
      <p role="status" className="notice" hidden={!exported}>
        Arquivo meus-dados.json baixado: conta, pets, agendamentos e lista de espera.
      </p>

      {step === 'idle' && (
        <p>
          <button type="button" className="button secondary" disabled={busy} onClick={() => setStep('confirm')}>
            Excluir minha conta
          </button>
        </p>
      )}

      {step === 'confirm' && (
        <div className="my-data__confirm" role="group" aria-label="Confirmar exclusão da conta">
          <p>
            <strong>Isso não tem volta.</strong> Seu nome, e-mail, telefone e os dados dos seus pets são removidos, e os horários que ainda estão marcados são cancelados. Os atendimentos
            já feitos ficam no estúdio, sem identificar você.
          </p>
          <Field
            label="Sua senha"
            name="delete-password"
            type="password"
            autoComplete="current-password"
            disabled={busy}
            value={password}
            error={fieldError}
            onChange={(e) => {
              setPassword(e.target.value)
              setFieldError(null)
            }}
          />
          <span className="actions">
            <button type="button" className="button" disabled={busy} onClick={remove}>
              {busy ? 'Excluindo…' : 'Excluir a conta para sempre'}
            </button>
            <button
              type="button"
              className="button secondary"
              disabled={busy}
              onClick={() => {
                setStep('idle')
                setPassword('')
                setFieldError(null)
                setProblem(null)
              }}
            >
              Voltar
            </button>
          </span>
        </div>
      )}

      <p role="alert" className="error" hidden={!problem}>
        {problem}
      </p>
    </section>
  )
}
