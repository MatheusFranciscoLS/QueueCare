import { validatePhone } from './phone'

/**
 * Leitura da planilha de clientes (ADR 0034). Tudo acontece no navegador: o arquivo não sobe para lugar nenhum; só as linhas já interpretadas vão para a API,
 * que valida tudo de novo. O Excel brasileiro salva CSV com ponto e vírgula e, muitas vezes, em Windows-1252 (acentos quebrariam em UTF-8): as duas coisas são tratadas.
 */

/** O que a API aceita por chamada (CounterImport.MAX_ROWS) e por hora (5 chamadas). */
export const IMPORT_CHUNK = 200
export const IMPORT_MAX_ROWS = 1000

/** Lê os bytes como texto: UTF-8 se for válido; senão Windows-1252 (o "CSV" do Excel). Tira a marca de ordem de bytes do começo. */
export function decodeCsv(buffer: ArrayBuffer): string {
  let text: string
  try {
    text = new TextDecoder('utf-8', { fatal: true }).decode(buffer)
  } catch {
    text = new TextDecoder('windows-1252').decode(buffer)
  }
  return text.replace(/^﻿/, '')
}

/** Divide o texto em linhas e células. Aceita aspas (com "" para aspas dentro), quebras de linha dentro de aspas e o separador ; , ou tabulação (o mais frequente na primeira linha). */
export function parseCsv(text: string): string[][] {
  const firstLine = text.split(/\r\n|\n|\r/, 1)[0] ?? ''
  const delimiter = [';', ',', '\t'].reduce((best, d) => (count(firstLine, d) > count(firstLine, best) ? d : best), ';')
  const rows: string[][] = []
  let row: string[] = []
  let cell = ''
  let quoted = false
  for (let i = 0; i < text.length; i++) {
    const c = text[i]
    if (quoted) {
      if (c === '"' && text[i + 1] === '"') {
        cell += '"'
        i++
      } else if (c === '"') {
        quoted = false
      } else {
        cell += c
      }
    } else if (c === '"') {
      quoted = true
    } else if (c === delimiter) {
      row.push(cell)
      cell = ''
    } else if (c === '\n' || c === '\r') {
      if (c === '\r' && text[i + 1] === '\n') i++
      row.push(cell)
      rows.push(row)
      row = []
      cell = ''
    } else {
      cell += c
    }
  }
  if (cell !== '' || row.length > 0) {
    row.push(cell)
    rows.push(row)
  }
  return rows.filter((r) => r.some((x) => x.trim() !== ''))
}

function count(text: string, char: string): number {
  return text.split(char).length - 1
}

function fold(text: string): string {
  return text
    .normalize('NFD')
    .replace(/\p{M}+/gu, '')
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, ' ')
    .trim()
}

type Field = 'name' | 'phone' | 'email' | 'petName' | 'petSpecies'

/** Nomes de coluna aceitos (já sem acento, minúsculos). */
const HEADERS: Record<Field, string[]> = {
  name: ['nome', 'nome do tutor', 'tutor', 'cliente', 'nome do cliente'],
  phone: ['telefone', 'celular', 'whatsapp', 'fone', 'tel', 'contato', 'telefone whatsapp'],
  email: ['email', 'e mail', 'e-mail'],
  petName: ['pet', 'nome do pet', 'animal', 'nome do animal'],
  petSpecies: ['especie', 'tipo', 'tipo do pet', 'especie do pet'],
}

const FIELD_LABEL: Record<Field, string> = { name: 'Nome', phone: 'Telefone', email: 'E-mail', petName: 'Pet', petSpecies: 'Espécie' }

/** "cachorro", "cão", "dog" → DOG; "gato", "gata", "cat" → CAT; qualquer outra coisa não é reconhecida (nunca se adivinha). */
export function parseSpecies(typed: string): 'DOG' | 'CAT' | null {
  const text = fold(typed)
  if (['cachorro', 'cachorra', 'cao', 'dog', 'canino'].includes(text)) return 'DOG'
  if (['gato', 'gata', 'cat', 'felino'].includes(text)) return 'CAT'
  return null
}

export interface ImportRow {
  name: string
  phone: string
  email: string
  petName: string
  petSpecies: 'DOG' | 'CAT' | null
}

export interface PreparedRow {
  /** O número da linha na planilha (a primeira linha é o cabeçalho, então os dados começam na 2). */
  line: number
  row: ImportRow
  /** O que impede de importar esta linha (vazio = pronta). */
  problems: string[]
  /** Tem pet, mas a espécie não foi reconhecida: o tutor entra e o pet fica de fora. */
  petIgnored: boolean
}

export interface PreparedImport {
  rows: PreparedRow[]
  /** Colunas obrigatórias que não foram achadas (então nada pode ser importado). */
  missing: string[]
  /** Passou do máximo por arquivo. */
  tooMany: boolean
}

/** Interpreta a tabela: acha as colunas pelo cabeçalho, confere cada linha e marca o telefone repetido dentro do próprio arquivo. */
export function prepareImport(table: string[][]): PreparedImport {
  if (table.length === 0) return { rows: [], missing: ['Nome', 'Telefone'], tooMany: false }
  const header = table[0].map(fold)
  const column = (field: Field) => header.findIndex((h) => HEADERS[field].includes(h))
  const index: Record<Field, number> = { name: column('name'), phone: column('phone'), email: column('email'), petName: column('petName'), petSpecies: column('petSpecies') }
  const missing = (['name', 'phone'] as Field[]).filter((f) => index[f] < 0).map((f) => FIELD_LABEL[f])
  if (missing.length > 0) return { rows: [], missing, tooMany: false }

  const body = table.slice(1)
  const seen = new Set<string>()
  const rows: PreparedRow[] = body.map((cells, i) => {
    const get = (field: Field) => (index[field] >= 0 ? (cells[index[field]] ?? '').trim() : '')
    const row: ImportRow = { name: get('name'), phone: get('phone'), email: get('email'), petName: get('petName'), petSpecies: parseSpecies(get('petSpecies')) }
    const problems: string[] = []
    if (!row.name) problems.push('sem nome')
    else if ([...row.name].length > 120) problems.push('nome grande demais')
    const phoneProblem = row.phone ? validatePhone(row.phone) : 'sem telefone'
    if (phoneProblem) problems.push(row.phone ? 'telefone inválido' : 'sem telefone')
    else {
      const digits = row.phone.replace(/\D/g, '')
      const key = digits.startsWith('55') && digits.length >= 12 ? digits : `55${digits}`
      if (seen.has(key)) problems.push('telefone repetido no arquivo')
      seen.add(key)
    }
    if (row.email && !/^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(row.email)) problems.push('e-mail inválido')
    const petIgnored = row.petName !== '' && row.petSpecies === null
    return { line: i + 2, row, problems, petIgnored }
  })
  return { rows, missing: [], tooMany: rows.length > IMPORT_MAX_ROWS }
}

/** O arquivo de exemplo que o dono baixa para preencher (com ponto e vírgula, como o Excel brasileiro abre). */
export const SAMPLE_CSV = 'Nome;Telefone;E-mail;Pet;Espécie\r\nMaria Souza;(11) 91234-5678;;Bidu;Cachorro\r\nJoão Lima;(21) 98888-7777;joao@exemplo.com;Mimi;Gato\r\n'
