import { useCallback, useMemo } from 'react'
import { Link } from 'react-router-dom'
import { isNotConfigured } from '../../api/errors'
import { providerApi } from '../../api/provider'
import { useAuth } from '../../auth/session'
import { useLoad } from '../useLoad'

interface Setup {
  business: boolean
  /** WhatsApp do estúdio informado (ADR 0021): é por ele que o tutor fala com o estúdio depois do prazo de cancelamento. */
  phone: boolean
  services: number
  windows: number
}

/** Os três passos do estúdio, na ordem em que fazem sentido: o negócio, o que oferece e quando atende. */
const STEPS = [
  { key: 'business', to: '/meu-negocio', title: 'Meu negócio', text: 'nome, fuso horário, antecedência, prazo de cancelamento e o WhatsApp do estúdio' },
  { key: 'services', to: '/servicos', title: 'Serviços', text: 'o que você oferece, quanto tempo leva e o preço' },
  { key: 'windows', to: '/expediente', title: 'Expediente', text: 'dias e horários de atendimento' },
] as const

/**
 * Configuração do estúdio no Início (ADR 0019, fatia 5): só aparece enquanto faltar algo, com o que já está pronto marcado. Quando tudo está
 * configurado, o Início fica só com o dia. Se não der para saber (erro), mostra os três atalhos de sempre.
 */
export function SetupPanel() {
  const { request } = useAuth()
  const api = useMemo(() => providerApi(request), [request])
  const load = useCallback(async (): Promise<Setup> => {
    let phone = false
    try {
      phone = !!(await api.getBusiness()).phone
    } catch (e) {
      if (isNotConfigured(e)) return { business: false, phone: false, services: 0, windows: 0 }
      throw e
    }
    const [services, windows] = await Promise.all([api.listServices(), api.getAvailability()])
    return { business: true, phone, services: services.filter((s) => s.active).length, windows: windows.length }
  }, [api])
  const { data, error, loading } = useLoad<Setup>(load)

  if (loading && !data) return null
  const done: Record<(typeof STEPS)[number]['key'], boolean> = { business: (data?.business ?? false) && (data?.phone ?? false), services: (data?.services ?? 0) > 0, windows: (data?.windows ?? 0) > 0 }
  if (data && done.business && done.services && done.windows) return null
  const unknown = !data && error !== undefined
  return (
    <>
      <h2>Configure o seu negócio</h2>
      <ol className="shortcuts setup">
        {STEPS.map((step) => (
          <li key={step.key} data-done={!unknown && done[step.key] ? 'true' : undefined}>
            <Link to={step.to}>{step.title}</Link>
            <span>
              {!unknown && done[step.key] && <strong className="setup-ok">Pronto · </strong>}
              {step.text}
            </span>
          </li>
        ))}
      </ol>
    </>
  )
}
