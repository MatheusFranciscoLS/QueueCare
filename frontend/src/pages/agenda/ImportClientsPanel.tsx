import { useMemo, useState } from 'react'
import { customersApi, type ImportResult } from '../../api/customers'
import { IMPORT_CHUNK, IMPORT_MAX_ROWS, SAMPLE_CSV, decodeCsv, parseCsv, prepareImport, type PreparedImport } from '../../api/importCsv'
import { describeError } from '../../api/messages'
import { useAuth } from '../../auth/session'
import { useSingleFlight } from '../useSingleFlight'

/** Quantas linhas com problema listar antes de dizer "e mais N" (a lista longa não ajuda ninguém). */
const MAX_LISTED_PROBLEMS = 12

/** O arquivo de exemplo, entregue como CSV com acentos válidos para o Excel (a marca de ordem de bytes faz o Excel abrir em UTF-8). */
function downloadSample() {
  const blob = new Blob(['﻿', SAMPLE_CSV], { type: 'text/csv;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = 'modelo-de-clientes.csv'
  document.body.appendChild(link)
  link.click()
  link.remove()
  URL.revokeObjectURL(url)
}

/**
 * Importar clientes de uma planilha (ADR 0034). O arquivo é lido AQUI, no navegador: nada dele sobe, só as linhas já conferidas, em blocos de 200. Antes de importar a tela
 * mostra quantas linhas estão prontas, quais têm problema e quais pets vão ficar de fora (espécie não reconhecida), para o dono corrigir a planilha se quiser.
 */
export function ImportClientsPanel({ onDone, onCancel }: { onDone: () => void; onCancel: () => void }) {
  const { request } = useAuth()
  const api = useMemo(() => customersApi(request), [request])
  const [prepared, setPrepared] = useState<PreparedImport | null>(null)
  const [fileProblem, setFileProblem] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [progress, setProgress] = useState(0)
  const [problem, setProblem] = useState<string | null>(null)
  const [result, setResult] = useState<ImportResult | null>(null)
  const once = useSingleFlight()

  async function choose(file: File | undefined) {
    setPrepared(null)
    setFileProblem(null)
    setProblem(null)
    if (!file) return
    try {
      const table = parseCsv(decodeCsv(await file.arrayBuffer()))
      const found = prepareImport(table)
      if (found.missing.length > 0) {
        setFileProblem(`Não achei ${found.missing.length === 1 ? 'a coluna' : 'as colunas'} ${found.missing.join(' e ')} na primeira linha. Use o modelo para conferir os nomes.`)
        return
      }
      if (found.rows.length === 0) {
        setFileProblem('A planilha não tem nenhuma linha de cliente abaixo do cabeçalho.')
        return
      }
      setPrepared(found)
    } catch {
      setFileProblem('Não consegui ler esse arquivo. Salve a planilha como CSV e tente de novo.')
    }
  }

  const ready = prepared?.rows.filter((r) => r.problems.length === 0) ?? []
  const withProblems = prepared?.rows.filter((r) => r.problems.length > 0) ?? []
  const petsIgnored = ready.filter((r) => r.petIgnored).length

  async function run() {
    if (!prepared || ready.length === 0 || prepared.tooMany) return
    await once(async () => {
      setBusy(true)
      setProblem(null)
      setProgress(0)
      const total: ImportResult = { created: 0, duplicates: 0, invalid: 0, emailTaken: 0, petsCreated: 0, petsSkipped: 0, rows: [] }
      try {
        for (let start = 0; start < ready.length; start += IMPORT_CHUNK) {
          const part = await api.importRows(ready.slice(start, start + IMPORT_CHUNK).map((r) => r.row))
          total.created += part.created
          total.duplicates += part.duplicates
          total.invalid += part.invalid
          total.emailTaken += part.emailTaken
          total.petsCreated += part.petsCreated
          total.petsSkipped += part.petsSkipped
          setProgress(Math.min(ready.length, start + IMPORT_CHUNK))
        }
        setResult(total)
      } catch (e) {
        // Os blocos que já entraram continuam entrando: importar de novo é seguro (o telefone repetido é pulado).
        setProblem(`${describeError(e)} O que já entrou continua cadastrado; importe o mesmo arquivo de novo para completar (quem já existe é pulado).`)
        if (total.created > 0) setResult(null)
      } finally {
        setBusy(false)
      }
    })
  }

  if (result) {
    return (
      <section className="card import" aria-labelledby="import-title">
        <h2 id="import-title">Importação concluída</h2>
        <p role="status" className="notice">
          {result.created === 1 ? '1 cliente cadastrado' : `${result.created} clientes cadastrados`}
          {result.petsCreated > 0 ? ` e ${result.petsCreated === 1 ? '1 pet' : `${result.petsCreated} pets`}` : ''}.
        </p>
        <ul className="import__summary">
          {result.duplicates > 0 && <li>{result.duplicates === 1 ? '1 já existia (mesmo telefone) e foi pulado.' : `${result.duplicates} já existiam (mesmo telefone) e foram pulados.`}</li>}
          {result.emailTaken > 0 && (
            <li>{result.emailTaken === 1 ? '1 tem' : `${result.emailTaken} têm`} e-mail que já está no app: o tutor agenda sozinho, então não {result.emailTaken === 1 ? 'foi cadastrado' : 'foram cadastrados'}.</li>
          )}
          {result.invalid > 0 && <li>{result.invalid === 1 ? '1 com dado inválido não foi cadastrado.' : `${result.invalid} com dado inválido não foram cadastrados.`}</li>}
          {result.petsSkipped > 0 && <li>{result.petsSkipped === 1 ? '1 pet ficou de fora' : `${result.petsSkipped} pets ficaram de fora`} por falta da espécie (cachorro ou gato).</li>}
        </ul>
        <p className="hint">Quem foi importado não tem acesso ao app: você agenda por ele em Clientes.</p>
        <span className="actions">
          <button type="button" className="button" onClick={onDone}>
            Concluir
          </button>
        </span>
      </section>
    )
  }

  return (
    <section className="card import" aria-labelledby="import-title">
      <h2 id="import-title">Importar clientes</h2>
      <p className="hint">
        Traga a lista que você já tem. Salve a planilha como CSV (no Excel: Arquivo, Salvar como, CSV). A primeira linha precisa ter os nomes das colunas: <strong>Nome</strong> e{' '}
        <strong>Telefone</strong> são obrigatórios; <strong>E-mail</strong>, <strong>Pet</strong> e <strong>Espécie</strong> (cachorro ou gato) são opcionais. O arquivo é lido aqui, no
        seu aparelho. Até {IMPORT_MAX_ROWS} linhas por vez.
      </p>
      <p>
        <button type="button" className="button secondary" onClick={downloadSample}>
          Baixar modelo
        </button>
      </p>
      <div className="field">
        <label htmlFor="import-file">Arquivo CSV</label>
        <input id="import-file" type="file" accept=".csv,text/csv,text/plain" disabled={busy} onChange={(e) => void choose(e.target.files?.[0])} />
      </div>

      {fileProblem && (
        <p role="alert" className="error">
          {fileProblem}
        </p>
      )}

      {prepared && (
        <div className="import__preview" aria-live="polite">
          {prepared.tooMany && (
            <p role="alert" className="error">
              A planilha tem {prepared.rows.length} linhas e o máximo por vez é {IMPORT_MAX_ROWS}. Divida em arquivos menores.
            </p>
          )}
          <p className="notice">
            {prepared.rows.length} linhas: <strong>{ready.length} prontas</strong>
            {withProblems.length > 0 ? `, ${withProblems.length} com problema (não serão importadas)` : ''}.
          </p>
          {petsIgnored > 0 && (
            <p className="hint">
              {petsIgnored === 1 ? '1 pet não será cadastrado' : `${petsIgnored} pets não serão cadastrados`} porque a espécie não foi reconhecida (use cachorro ou gato); o tutor entra do mesmo jeito.
            </p>
          )}
          {withProblems.length > 0 && (
            <>
              <h3>Linhas com problema</h3>
              <ul className="rows import__problems">
                {withProblems.slice(0, MAX_LISTED_PROBLEMS).map((r) => (
                  <li key={r.line} className="row">
                    <span>
                      Linha {r.line}: {r.problems.join(', ')}
                    </span>
                  </li>
                ))}
              </ul>
              {withProblems.length > MAX_LISTED_PROBLEMS && <p className="hint">E mais {withProblems.length - MAX_LISTED_PROBLEMS} linhas com problema.</p>}
            </>
          )}
        </div>
      )}

      {problem && (
        <p role="alert" className="error">
          {problem}
        </p>
      )}
      {busy && (
        <p role="status" className="notice">
          Importando… {progress} de {ready.length}
        </p>
      )}
      <span className="actions">
        <button type="button" className="button" disabled={busy || !prepared || ready.length === 0 || prepared.tooMany} onClick={run}>
          {busy ? 'Importando…' : ready.length > 0 ? `Importar ${ready.length} ${ready.length === 1 ? 'cliente' : 'clientes'}` : 'Importar'}
        </button>
        <button type="button" className="button secondary" disabled={busy} onClick={onCancel}>
          Cancelar
        </button>
      </span>
    </section>
  )
}
