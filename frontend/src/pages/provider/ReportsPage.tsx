import { useCallback, useMemo, useState } from 'react'
import { agendaApi, type Workplace } from '../../api/agenda'
import { longDate, todayIn } from '../../api/dates'
import { describeError } from '../../api/messages'
import { formatMoney } from '../../api/money'
import { formatPhone, whatsappLink } from '../../api/phone'
import { comeBackMessage, PERIODS, periodRange, reportsApi, type LapsedCustomer, type PeriodKey, type ReportSummary } from '../../api/reports'
import { useAuth } from '../../auth/session'
import { Loading } from '../Loading'
import { RefreshWarning } from '../RefreshWarning'
import { useLoad } from '../useLoad'

const LAPSED_OPTIONS = [30, 60, 90]
/** Acima disto o gráfico de barras fica miúdo demais: só a tabela. */
const MAX_BARS = 31

/**
 * Resumo do estúdio (ADR 0031): o que o dono consulta para saber como vai o negócio. Números contados de agendamentos que já existem; os valores têm nome honesto
 * (concluídos, previsto, sinais recebidos) e a tela diz que NÃO são faturamento. Só o dono vê (tem dinheiro e nome de cliente).
 */
export function ReportsPage() {
  const { request } = useAuth()
  const agenda = useMemo(() => agendaApi(request), [request])
  const workplaceLoad = useCallback(() => agenda.workplace(), [agenda])
  const workplace = useLoad<Workplace>(workplaceLoad)

  if (workplace.loading && !workplace.data) return <Loading />
  if (workplace.error !== undefined && !workplace.data) {
    return (
      <p role="alert" className="error">
        {describeError(workplace.error)}
      </p>
    )
  }
  if (!workplace.data) return null
  return <Report workplace={workplace.data} />
}

function Report({ workplace }: { workplace: Workplace }) {
  const { request } = useAuth()
  const api = useMemo(() => reportsApi(request), [request])
  const today = todayIn(workplace.timeZone)
  const [period, setPeriod] = useState<PeriodKey>('7d')
  const range = periodRange(period, today)
  const load = useCallback(() => api.summary(range.from, range.to), [api, range.from, range.to])
  const { data, error, loading, reload } = useLoad<ReportSummary>(load)

  return (
    <section className="card report">
      <h1>Resumo</h1>
      <p className="hint">
        {workplace.businessName} · {range.from === range.to ? longDate(range.from) : `${longDate(range.from)} a ${longDate(range.to)}`}
      </p>

      <div role="group" aria-label="Período" className="report__periods">
        {PERIODS.map((p) => (
          <button key={p.key} type="button" className={period === p.key ? 'button' : 'button secondary'} aria-pressed={period === p.key} onClick={() => setPeriod(p.key)}>
            {p.label}
          </button>
        ))}
      </div>

      {loading && !data && <Loading />}
      {error !== undefined && data && <RefreshWarning onRetry={reload} />}
      {error !== undefined && !data && (
        <p role="alert" className="error">
          {describeError(error)}
        </p>
      )}
      {data && <SummaryView data={data} />}

      <Lapsed studioName={workplace.businessName} />
    </section>
  )
}

function Stat({ label, value, tone }: { label: string; value: string | number; tone?: 'warn' }) {
  return (
    <div className={tone ? `report__stat report__stat--${tone}` : 'report__stat'}>
      <dt>{label}</dt>
      <dd>{value}</dd>
    </div>
  )
}

function SummaryView({ data }: { data: ReportSummary }) {
  const { counts, amounts } = data
  const empty = counts.completed + counts.noShow + counts.cancelled + counts.scheduled + counts.inProgress === 0
  const maxDay = Math.max(1, ...data.byDay.map((d) => d.completed + d.noShow + d.cancelled))
  return (
    <>
      {empty && <p className="notice">Nenhum atendimento neste período.</p>}

      <h2>Atendimentos</h2>
      <dl className="report__stats">
        <Stat label="Concluídos" value={counts.completed} />
        <Stat label="Faltas" value={counts.noShow} tone={counts.noShow > 0 ? 'warn' : undefined} />
        <Stat label="Cancelados" value={counts.cancelled} />
        <Stat label="Marcados" value={counts.scheduled} />
        {counts.inProgress > 0 && <Stat label="Em andamento" value={counts.inProgress} />}
      </dl>

      <h2>Valores</h2>
      <dl className="report__stats">
        <Stat label="Atendimentos concluídos" value={formatMoney(amounts.completedCents)} />
        <Stat label="Ainda por concluir (previsto)" value={formatMoney(amounts.expectedCents)} />
        <Stat label="Sinais Pix recebidos" value={formatMoney(amounts.depositsReceivedCents)} />
      </dl>
      <p className="hint">
        São os valores de tabela dos atendimentos, congelados na reserva. <strong>Não são faturamento</strong>: o app não sabe o que foi pago no balcão nem os descontos. Falta e cancelamento nunca entram.
      </p>

      {data.byService.length > 0 && (
        <>
          <h2>Por serviço</h2>
          <table className="report__table">
            <caption className="pp-sr">Atendimentos concluídos por serviço</caption>
            <thead>
              <tr>
                <th scope="col">Serviço</th>
                <th scope="col">Concluídos</th>
                <th scope="col">Valor</th>
              </tr>
            </thead>
            <tbody>
              {data.byService.map((row) => (
                <tr key={row.service}>
                  <th scope="row">{row.service}</th>
                  <td>{row.completed}</td>
                  <td>{formatMoney(row.completedCents)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </>
      )}

      {!empty && (
        <>
          <h2>Por dia</h2>
          {data.byDay.length <= MAX_BARS && (
            <ol className="report__bars" aria-hidden="true">
              {data.byDay.map((d) => (
                <li key={d.date} title={`${d.date}: ${d.completed} concluídos, ${d.noShow} faltas, ${d.cancelled} cancelados`}>
                  <span className="report__bar" style={{ height: `${((d.completed + d.noShow + d.cancelled) / maxDay) * 100}%` }}>
                    <span className="report__bar-missed" style={{ height: `${((d.noShow + d.cancelled) / Math.max(1, d.completed + d.noShow + d.cancelled)) * 100}%` }} />
                  </span>
                  <span className="report__bar-label">{d.date.slice(8)}</span>
                </li>
              ))}
            </ol>
          )}
          {data.byDay.length <= MAX_BARS && (
            <p className="hint report__legend">
              <span className="report__swatch" aria-hidden="true" /> concluídos · <span className="report__swatch report__swatch--missed" aria-hidden="true" /> faltas e cancelamentos
            </p>
          )}
          <details className="report__days">
            <summary>Ver a tabela por dia</summary>
            <table className="report__table">
              <caption className="pp-sr">Atendimentos por dia</caption>
              <thead>
                <tr>
                  <th scope="col">Dia</th>
                  <th scope="col">Concluídos</th>
                  <th scope="col">Faltas</th>
                  <th scope="col">Cancelados</th>
                </tr>
              </thead>
              <tbody>
                {data.byDay.map((d) => (
                  <tr key={d.date}>
                    <th scope="row">{d.date.slice(8)}/{d.date.slice(5, 7)}</th>
                    <td>{d.completed}</td>
                    <td>{d.noShow}</td>
                    <td>{d.cancelled}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </details>
        </>
      )}

      {data.topNoShows.length > 0 && (
        <>
          <h2>Quem mais faltou</h2>
          <ul className="rows">
            {data.topNoShows.map((n) => (
              <li key={n.customerId} className="row">
                <span>
                  <strong>{n.name}</strong> · {n.noShows === 1 ? '1 falta' : `${n.noShows} faltas`}
                </span>
              </li>
            ))}
          </ul>
        </>
      )}
    </>
  )
}

/** Clientes que sumiram: atendidos há mais de N dias e sem horário marcado. A equipe chama pelo WhatsApp (o app só prepara a mensagem). */
function Lapsed({ studioName }: { studioName: string }) {
  const { request } = useAuth()
  const api = useMemo(() => reportsApi(request), [request])
  const [days, setDays] = useState(60)
  const load = useCallback(() => api.lapsed(days), [api, days])
  const { data, error, loading, reload } = useLoad<LapsedCustomer[]>(load)

  return (
    <section aria-labelledby="lapsed-title" className="report__lapsed">
      <h2 id="lapsed-title">Clientes que sumiram</h2>
      <p className="hint">Já foram atendidos aqui, não voltam há mais de {days} dias e não têm horário marcado.</p>
      <div role="group" aria-label="Sem voltar há mais de" className="report__periods">
        {LAPSED_OPTIONS.map((d) => (
          <button key={d} type="button" className={days === d ? 'button' : 'button secondary'} aria-pressed={days === d} onClick={() => setDays(d)}>
            {d} dias
          </button>
        ))}
      </div>
      {loading && !data && <Loading />}
      {error !== undefined && data && <RefreshWarning onRetry={reload} />}
      {error !== undefined && !data && (
        <p role="alert" className="error">
          {describeError(error)}
        </p>
      )}
      {data && data.length === 0 && <p className="notice">Ninguém sumiu por mais de {days} dias. Bom sinal.</p>}
      {data && data.length > 0 && (
        <ul className="rows">
          {data.map((c) => (
            <li key={c.customerId} className="row report__lapsed-row">
              <span>
                <strong>{c.name}</strong> · última visita há {c.daysSince} dias
                {c.phone && <span className="hint"> · {formatPhone(c.phone)}</span>}
              </span>
              {c.phone && (
                <a className="button secondary" href={whatsappLink(c.phone, comeBackMessage(c.name, studioName))} target="_blank" rel="noopener noreferrer">
                  Chamar no WhatsApp
                  <span className="pp-sr"> ({c.name})</span>
                </a>
              )}
            </li>
          ))}
        </ul>
      )}
    </section>
  )
}
