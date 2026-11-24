import { act, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { decodeCsv, parseCsv, parseSpecies, prepareImport, SAMPLE_CSV } from '../api/importCsv'
import { json, mockApi, PASSWORD, problem, renderWithHarness, token, USER, type Call } from './helpers'

vi.mock('../pages/customer/CustomerPanel', () => ({ CustomerPanel: () => null }))
vi.mock('../pages/provider/SetupPanel', () => ({ SetupPanel: () => null }))
vi.mock('../pages/TodaySummary', () => ({ TodaySummary: () => null }))
afterEach(() => vi.unstubAllGlobals())

type Routes = Record<string, (call: Call) => Response | Promise<Response>>

describe('leitura da planilha (funções)', () => {
  it('decodifica UTF-8 (com ou sem marca de ordem de bytes) e cai para Windows-1252, o CSV do Excel', () => {
    const utf8 = new TextEncoder().encode('Nome;Espécie\nJoão;Gato')
    expect(decodeCsv(utf8.buffer as ArrayBuffer)).toBe('Nome;Espécie\nJoão;Gato')
    const withBom = new Uint8Array([0xef, 0xbb, 0xbf, ...utf8])
    expect(decodeCsv(withBom.buffer as ArrayBuffer)).toBe('Nome;Espécie\nJoão;Gato')
    // "João" em Windows-1252: a letra ã é o byte 0xE3, que não é UTF-8 válido
    const latin1 = new Uint8Array([0x4a, 0x6f, 0xe3, 0x6f])
    expect(decodeCsv(latin1.buffer as ArrayBuffer)).toBe('João')
  })

  it('detecta ponto e vírgula, vírgula e tabulação; respeita aspas, aspas duplas e quebra dentro de aspas; ignora linhas em branco', () => {
    expect(parseCsv('a;b;c\n1;2;3')).toEqual([['a', 'b', 'c'], ['1', '2', '3']])
    expect(parseCsv('a,b,c\r\n1,2,3\r\n')).toEqual([['a', 'b', 'c'], ['1', '2', '3']])
    expect(parseCsv('a\tb\n1\t2')).toEqual([['a', 'b'], ['1', '2']])
    expect(parseCsv('nome,obs\n"Silva, João","disse ""oi""\nna linha 2"\n\n,\n')).toEqual([['nome', 'obs'], ['Silva, João', 'disse "oi"\nna linha 2']])
  })

  it('reconhece a espécie sem adivinhar', () => {
    expect(parseSpecies('Cachorro')).toBe('DOG')
    expect(parseSpecies(' CÃO ')).toBe('DOG')
    expect(parseSpecies('gata')).toBe('CAT')
    expect(parseSpecies('coelho')).toBeNull()
    expect(parseSpecies('')).toBeNull()
  })

  it('o modelo que o dono baixa é lido sem nenhum problema', () => {
    const prepared = prepareImport(parseCsv(SAMPLE_CSV))
    expect(prepared.missing).toEqual([])
    expect(prepared.rows).toHaveLength(2)
    expect(prepared.rows.every((r) => r.problems.length === 0)).toBe(true)
    expect(prepared.rows[0].row).toMatchObject({ name: 'Maria Souza', petName: 'Bidu', petSpecies: 'DOG', email: '' })
    expect(prepared.rows[1].row).toMatchObject({ email: 'joao@exemplo.com', petSpecies: 'CAT' })
  })

  it('acha as colunas por nomes comuns, em qualquer ordem e sem acento, e diz as que faltam', () => {
    const ok = prepareImport(parseCsv('Celular;Cliente;Animal;Tipo\n(11) 91234-5678;Ana;Rex;cachorro'))
    expect(ok.missing).toEqual([])
    expect(ok.rows[0].row).toMatchObject({ name: 'Ana', phone: '(11) 91234-5678', petName: 'Rex', petSpecies: 'DOG' })
    expect(prepareImport(parseCsv('Nome;Pet\nAna;Rex')).missing).toEqual(['Telefone'])
    expect(prepareImport(parseCsv('Foo;Bar\n1;2')).missing).toEqual(['Nome', 'Telefone'])
    expect(prepareImport([]).missing).toEqual(['Nome', 'Telefone'])
  })

  it('marca o que impede a linha, com o número da linha da planilha, e o telefone repetido no arquivo', () => {
    const prepared = prepareImport(parseCsv(['Nome;Telefone;Email;Pet;Espécie', ';(11) 91234-5678;;;', 'Sem Fone;;;;', 'Fone Ruim;123;;;', 'Email Ruim;(11) 92222-3333;isso;;', 'Ok;(11) 96666-7777;;Bidu;', 'Repetido;11 96666-7777;;;'].join('\n')))
    const problems = prepared.rows.map((r) => r.problems)
    expect(problems[0]).toEqual(['sem nome'])
    expect(problems[1]).toEqual(['sem telefone'])
    expect(problems[2]).toEqual(['telefone inválido'])
    expect(problems[3]).toEqual(['e-mail inválido'])
    expect(problems[4]).toEqual([])
    expect(problems[5]).toEqual(['telefone repetido no arquivo'])
    expect(prepared.rows[0].line).toBe(2)
    expect(prepared.rows[4].petIgnored).toBe(true) // tem pet, sem espécie reconhecida
  })

  it('avisa quando passa do máximo por arquivo', () => {
    const lines = ['Nome;Telefone']
    for (let i = 0; i < 1001; i++) lines.push(`Pessoa ${i};(11) 9${String(i).padStart(4, '0')}-0000`)
    expect(prepareImport(parseCsv(lines.join('\n'))).tooMany).toBe(true)
  })
})

const ZONE = 'America/Sao_Paulo'

async function openClientes(role: 'PROVIDER' | 'ATTENDANT', routes: Routes) {
  const { calls } = mockApi({
    'POST /api/auth/login': () => token(),
    'GET /api/me': () => json(200, { ...USER, role, name: 'Maria Souza' }),
    'GET /api/agenda/workplace': () => json(200, { providerId: 'p-1', businessName: 'Estúdio', timeZone: ZONE, cancellationDeadlineMinutes: 720 }),
    'GET /api/agenda/customers?q=': () => json(200, []),
    ...routes,
  })
  const view = renderWithHarness()
  await act(async () => view.api().login('x@exemplo.test', PASSWORD))
  await screen.findByRole('heading', { name: 'Olá, Maria Souza' })
  const user = userEvent.setup()
  await user.click(within(screen.getByRole('navigation')).getByRole('link', { name: 'Clientes' }))
  await screen.findByRole('heading', { level: 1, name: 'Clientes' })
  return { calls, user }
}

const csvFile = (text: string) => new File([text], 'clientes.csv', { type: 'text/csv' })

describe('Importar planilha', () => {
  beforeEach(() => {
    URL.createObjectURL = vi.fn(() => 'blob:modelo')
    URL.revokeObjectURL = vi.fn()
  })

  it('só o dono vê "Importar planilha"', async () => {
    await openClientes('ATTENDANT', {})
    await screen.findByRole('button', { name: 'Novo cliente' })
    expect(screen.queryByRole('button', { name: 'Importar planilha' })).not.toBeInTheDocument()
  })

  it('mostra quantas linhas estão prontas, as com problema (com a linha) e os pets que ficam de fora, antes de importar', async () => {
    const { calls, user } = await openClientes('PROVIDER', {})
    await user.click(await screen.findByRole('button', { name: 'Importar planilha' }))
    await user.upload(screen.getByLabelText('Arquivo CSV'), csvFile('Nome;Telefone;Pet;Espécie\nAna;(11) 91234-5678;Rex;\nBia;123;;\nCarla;(11) 97777-6666;Mimi;Gata\n'))

    expect(await screen.findByText(/3 linhas:/)).toHaveTextContent('3 linhas: 2 prontas, 1 com problema')
    expect(screen.getByText('Linha 3: telefone inválido')).toBeInTheDocument()
    expect(screen.getByText(/1 pet não será cadastrado porque a espécie não foi reconhecida/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Importar 2 clientes' })).toBeEnabled()
    expect(calls.some((c) => c.path.endsWith('/import'))).toBe(false) // só conferiu: nada foi enviado
  })

  it('importa só as linhas prontas, mostra o resultado e recarrega a lista', async () => {
    let sent: unknown
    const { user } = await openClientes('PROVIDER', {
      'POST /api/agenda/customers/import': (call) => {
        sent = call.body
        return json(200, { created: 2, duplicates: 0, invalid: 0, emailTaken: 0, petsCreated: 1, petsSkipped: 1, rows: [] })
      },
    })
    await user.click(await screen.findByRole('button', { name: 'Importar planilha' }))
    await user.upload(screen.getByLabelText('Arquivo CSV'), csvFile('Nome;Telefone;Email;Pet;Espécie\nAna;(11) 91234-5678;;Rex;\nBia;123;;;\nCarla;(11) 97777-6666;carla@exemplo.test;Mimi;Gata\n'))
    await user.click(await screen.findByRole('button', { name: 'Importar 2 clientes' }))

    expect(await screen.findByRole('heading', { name: 'Importação concluída' })).toBeInTheDocument()
    expect(screen.getByRole('status')).toHaveTextContent('2 clientes cadastrados e 1 pet')
    expect(screen.getByText(/1 pet ficou de fora por falta da espécie/)).toBeInTheDocument()
    expect(sent).toEqual({
      rows: [
        { name: 'Ana', phone: '(11) 91234-5678', email: null, petName: 'Rex', petSpecies: null },
        { name: 'Carla', phone: '(11) 97777-6666', email: 'carla@exemplo.test', petName: 'Mimi', petSpecies: 'CAT' },
      ],
    })
    await user.click(screen.getByRole('button', { name: 'Concluir' }))
    expect(screen.queryByRole('heading', { name: 'Importação concluída' })).not.toBeInTheDocument()
  })

  it('o resultado explica os pulados: telefone que já existia, e-mail que já está no app e dado inválido', async () => {
    const { user } = await openClientes('PROVIDER', { 'POST /api/agenda/customers/import': () => json(200, { created: 1, duplicates: 2, invalid: 1, emailTaken: 1, petsCreated: 0, petsSkipped: 0, rows: [] }) })
    await user.click(await screen.findByRole('button', { name: 'Importar planilha' }))
    await user.upload(screen.getByLabelText('Arquivo CSV'), csvFile('Nome;Telefone\nAna;(11) 91234-5678\n'))
    await user.click(await screen.findByRole('button', { name: 'Importar 1 cliente' }))
    expect(await screen.findByText(/2 já existiam \(mesmo telefone\) e foram pulados/)).toBeInTheDocument()
    expect(screen.getByText(/1 tem e-mail que já está no app/)).toBeInTheDocument()
    expect(screen.getByText(/1 com dado inválido não foi cadastrado/)).toBeInTheDocument()
  })

  it('arquivo sem as colunas obrigatórias explica o que falta e não deixa importar', async () => {
    const { user } = await openClientes('PROVIDER', {})
    await user.click(await screen.findByRole('button', { name: 'Importar planilha' }))
    await user.upload(screen.getByLabelText('Arquivo CSV'), csvFile('Nome;Pet\nAna;Rex\n'))
    expect(await screen.findByRole('alert')).toHaveTextContent('Não achei a coluna Telefone')
    expect(screen.getByRole('button', { name: 'Importar' })).toBeDisabled()
  })

  it('falha na API mostra o motivo e orienta a importar de novo (quem já entrou é pulado)', async () => {
    const { user } = await openClientes('PROVIDER', { 'POST /api/agenda/customers/import': () => problem(429, 'muitas', {}, { 'Retry-After': '600' }) })
    await user.click(await screen.findByRole('button', { name: 'Importar planilha' }))
    await user.upload(screen.getByLabelText('Arquivo CSV'), csvFile('Nome;Telefone\nAna;(11) 91234-5678\n'))
    await user.click(await screen.findByRole('button', { name: 'Importar 1 cliente' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('importe o mesmo arquivo de novo')
    await waitFor(() => expect(screen.getByRole('button', { name: 'Importar 1 cliente' })).toBeEnabled())
  })

  it('"Baixar modelo" entrega o arquivo de exemplo', async () => {
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => undefined)
    const { user } = await openClientes('PROVIDER', {})
    await user.click(await screen.findByRole('button', { name: 'Importar planilha' }))
    await user.click(screen.getByRole('button', { name: 'Baixar modelo' }))
    expect(click).toHaveBeenCalled()
    expect(URL.createObjectURL).toHaveBeenCalled()
    click.mockRestore()
  })
})
