import { useCallback } from 'react'
import { Link, useParams } from 'react-router-dom'
import { ApiError } from '../api/client'
import { describeError } from '../api/messages'
import { publicPolicyText } from '../api/policy'
import { fetchPublicStudio, type PublicService, type PublicStudio } from '../api/publicStudio'
import { useAuth } from '../auth/session'
import { Loading } from './Loading'
import { useLoad } from './useLoad'

/** "60 min" ou "a partir de 45 min" quando o tempo muda com o porte ou o pelo do pet. */
function durationText(s: PublicService): string {
  return s.durationVaries ? `a partir de ${s.durationMinutes} min` : `${s.durationMinutes} min`
}

/**
 * Página pública do estúdio (ADR 0015), sem login: nome, serviços e a regra de cancelamento. Quem quer agendar entra ou cria conta de cliente
 * (reservar sem conta é outro ADR, com mais risco de abuso). Endereço inexistente, não publicado ou de dono inativo: a MESMA resposta.
 */
export function PublicStudioPage() {
  const { slug = '' } = useParams()
  const { user } = useAuth()
  const load = useCallback(() => fetchPublicStudio(slug), [slug])
  const { data, error, loading } = useLoad<PublicStudio>(load)

  const content = (
    <section className="card public-studio">
      {loading && !data && <Loading>Carregando o estúdio…</Loading>}
      {error instanceof ApiError && error.status === 404 && (
        <>
          <h1>Estúdio não encontrado</h1>
          <p className="lead">Confira o endereço do link. O estúdio pode não ter publicado a página ainda.</p>
          <p>
            <Link to="/">Ir para o início</Link>
          </p>
        </>
      )}
      {error !== undefined && !(error instanceof ApiError && error.status === 404) && !data && (
        <p role="alert" className="error">
          {describeError(error)}
        </p>
      )}
      {data && <Studio studio={data} role={user?.role} />}
    </section>
  )
  // Quem está logado já tem a casca do app (menu e conta). O visitante vê só a marca e o conteúdo, logo no topo: o painel ilustrado da entrada empurraria o
  // nome do estúdio para fora da tela do celular.
  return user ? (
    content
  ) : (
    <div className="public-shell">
      <header>
        <Link to="/" className="pp-brand">
          <span>
            Pelo <b>&amp;</b> Ponto
          </span>
        </Link>
      </header>
      {content}
    </div>
  )
}

function Studio({ studio, role }: { studio: PublicStudio; role: string | undefined }) {
  return (
    <>
      <h1>{studio.businessName}</h1>
      <p className="lead">Banho e tosa com horário marcado. Escolha o serviço e agende pelo Pelo &amp; Ponto.</p>
      <Cta role={role} />

      <h2>Serviços</h2>
      {studio.services.length === 0 ? (
        <p className="notice">Este estúdio ainda não cadastrou serviços.</p>
      ) : (
        <ul className="rows public-services">
          {studio.services.map((s) => (
            <li key={s.name} className="row">
              <span>
                <strong>{s.name}</strong>
              </span>
              <span className="hint">{durationText(s)}</span>
            </li>
          ))}
        </ul>
      )}

      <h2>Cancelamento e remarcação</h2>
      <p className="public-policy">{publicPolicyText(studio.cancellationDeadlineMinutes)}</p>

      <Cta role={role} />
    </>
  )
}

/** O caminho para agendar: o visitante entra ou cria conta; o tutor já logado vai direto. Aparece no topo (quem já se decidiu) e no fim (quem leu tudo). Staff e admin não têm o que agendar. */
function Cta({ role }: { role: string | undefined }) {
  if (role !== undefined && role !== 'CUSTOMER') return null
  return (
    <p className="actions public-cta">
      {role === 'CUSTOMER' ? (
        <Link to="/agendar" className="button">
          Agendar
        </Link>
      ) : (
        <>
          <Link to="/entrar" className="button">
            Entrar para agendar
          </Link>
          <Link to="/cadastro" className="button secondary">
            Criar conta de cliente
          </Link>
        </>
      )}
    </p>
  )
}
