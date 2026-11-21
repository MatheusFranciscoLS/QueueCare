import { useCallback, useMemo, useState, type FormEvent } from 'react'
import { ApiError } from '../../api/client'
import { describeError } from '../../api/messages'
import { publicPath, type PublicPageSettings } from '../../api/publicStudio'
import { providerApi } from '../../api/provider'
import { useAuth } from '../../auth/session'
import { Field } from '../Field'
import { useLoad } from '../useLoad'
import { useSingleFlight } from '../useSingleFlight'

const SLUG = /^[a-z0-9]+(-[a-z0-9]+)*$/
const SLUG_HELP = 'Use de 3 a 40 letras minúsculas, números e hífens entre palavras. Exemplo: estudio-bem-te-vi.'

/**
 * Página pública do estúdio (ADR 0015): o endereço do link e se a página está publicada. Só o dono configura; publicada, qualquer pessoa com o link vê o
 * nome, os serviços e a política de cancelamento, sem login. Mudar o endereço invalida o link antigo.
 */
export function PublicPageForm() {
  const { request } = useAuth()
  const api = useMemo(() => providerApi(request), [request])
  const load = useCallback(() => api.getPublicPage(), [api])
  const { data, reload, version } = useLoad<PublicPageSettings>(load)
  // O aviso "salvo" mora aqui: depois de salvar a tela recarrega e o formulário é refeito com o que o SERVIDOR devolveu.
  const [saved, setSaved] = useState(false)
  if (!data) return null
  return <Form key={version} settings={data} saved={saved} setSaved={setSaved} onSaved={reload} />
}

function Form({ settings, saved, setSaved, onSaved }: { settings: PublicPageSettings; saved: boolean; setSaved: (value: boolean) => void; onSaved: () => Promise<void> }) {
  const { request } = useAuth()
  const api = useMemo(() => providerApi(request), [request])
  const [slug, setSlug] = useState(settings.slug ?? '')
  const [published, setPublished] = useState(settings.published)
  const [error, setError] = useState<string | undefined>()
  const [formError, setFormError] = useState<string | undefined>()
  const [copied, setCopied] = useState<'yes' | 'no' | null>(null)
  const [busy, setBusy] = useState(false)
  const once = useSingleFlight()

  const link = settings.published && settings.slug ? `${window.location.origin}${publicPath(settings.slug)}` : null

  async function submit(event: FormEvent) {
    event.preventDefault()
    await once(async () => {
      const typed = slug.trim()
      if (typed && (typed.length < 3 || typed.length > 40 || !SLUG.test(typed))) {
        setError(SLUG_HELP)
        return
      }
      if (published && !typed) {
        setError('Escolha um endereço para publicar a página.')
        return
      }
      setError(undefined)
      setFormError(undefined)
      setSaved(false)
      setBusy(true)
      try {
        await api.updatePublicPage({ slug: typed || null, published })
        setSaved(true)
        await onSaved()
      } catch (e) {
        if (e instanceof ApiError && e.status === 400 && e.fieldErrors.slug) setError(e.fieldErrors.slug)
        else setFormError(describeError(e))
      } finally {
        setBusy(false)
      }
    })
  }

  async function copy() {
    if (!link) return
    try {
      await navigator.clipboard.writeText(link)
      setCopied('yes')
    } catch {
      setCopied('no')
    }
  }

  return (
    <section className="card public-page-form" aria-label="Página pública">
      <h2>Página pública</h2>
      <p className="hint">
        Um link para compartilhar no WhatsApp e nas redes: mostra o nome do estúdio, os serviços e a política de cancelamento, sem pedir login. Para agendar, a pessoa entra ou
        cria uma conta de cliente.
      </p>
      <form onSubmit={submit} noValidate>
        <Field
          label="Endereço do estúdio"
          name="public-slug"
          disabled={busy}
          autoCapitalize="none"
          autoComplete="off"
          spellCheck={false}
          hint={`O link fica ${window.location.origin}/p/${slug.trim() || 'seu-endereco'}`}
          value={slug}
          error={error}
          onChange={(e) => {
            setSlug(e.target.value.toLowerCase())
            setError(undefined)
            setSaved(false)
          }}
        />
        <label className="check">
          <input type="checkbox" disabled={busy} checked={published} onChange={(e) => { setPublished(e.target.checked); setSaved(false); setError(undefined) }} />
          <span>Publicar a página (qualquer pessoa com o link poderá ver)</span>
        </label>
        <p role="alert" className="error" hidden={!formError}>
          {formError}
        </p>
        <p role="status" className="notice" hidden={!saved}>
          Página pública salva.
        </p>
        <button type="submit" className="button" disabled={busy}>
          {busy ? 'Salvando…' : 'Salvar página pública'}
        </button>
      </form>
      {link && (
        <p className="public-link">
          Seu link: <a href={link} target="_blank" rel="noopener noreferrer">{link}</a>{' '}
          <button type="button" className="button secondary" onClick={copy}>
            Copiar link
          </button>
          <span role="status" className="hint">
            {copied === 'yes' && ' Link copiado.'}
            {copied === 'no' && ' Não deu para copiar: selecione o link acima.'}
          </span>
        </p>
      )}
    </section>
  )
}
