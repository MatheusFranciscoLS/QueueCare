// Garante que o build de PRODUÇÃO não carrega a página de demonstração da fundação visual (ADR 0018, R2). (As fontes novas entraram na
// produção no R3, com a casca nova; só a demonstração continua proibida.) Procura, em TODOS os arquivos de dist/, o conteúdo e os nomes que só existem na demonstração.
// Uso: `npm run build && npm run check:build` (roda na CI depois do build).
import { readdirSync, readFileSync, statSync } from 'node:fs'
import { join, resolve } from 'node:path'

const dist = resolve(import.meta.dirname, '..', 'dist')
const PROIBIDOS = ['/__ui', 'Fundação visual', 'DemoPage']

function arquivos(dir) {
  return readdirSync(dir).flatMap((nome) => {
    const caminho = join(dir, nome)
    return statSync(caminho).isDirectory() ? arquivos(caminho) : [caminho]
  })
}

const achados = []
for (const arquivo of arquivos(dist)) {
  const nome = arquivo.toLowerCase()
  const texto = /\.(js|css|html|map|json|txt)$/.test(nome) ? readFileSync(arquivo, 'utf8') : ''
  for (const termo of PROIBIDOS) {
    if (nome.includes(termo.toLowerCase()) || texto.includes(termo)) achados.push(`${arquivo.slice(dist.length + 1)}: "${termo}"`)
  }
}
if (achados.length > 0) {
  console.error('O build de produção contém o que só deveria existir em desenvolvimento:\n' + achados.join('\n'))
  process.exit(1)
}
console.log(`build de produção limpo: nenhum dos ${PROIBIDOS.length} marcadores da demonstração em ${arquivos(dist).length} arquivos`)

// Política de segurança do site (ADR 0038). O desenvolvimento NÃO aplica a política (ela vem do vercel.json, só na Vercel), então um script em linha ou um endereço de API fora
// dela só quebraria em PRODUÇÃO. Estas conferências trazem o erro para a CI.
const problemas = []
const vercel = JSON.parse(readFileSync(resolve(import.meta.dirname, '..', 'vercel.json'), 'utf8'))
const cabecalhos = Object.fromEntries((vercel.headers?.find((h) => h.source === '/(.*)')?.headers ?? []).map((h) => [h.key, h.value]))
const csp = cabecalhos['Content-Security-Policy']
if (!csp) {
  problemas.push('vercel.json não define o cabeçalho Content-Security-Policy')
} else {
  const diretivas = Object.fromEntries(csp.split(';').map((d) => d.trim()).filter(Boolean).map((d) => [d.split(/\s+/)[0], d.split(/\s+/).slice(1)]))
  for (const exigida of ['default-src', 'script-src', 'connect-src', 'object-src', 'base-uri', 'frame-ancestors']) {
    if (!diretivas[exigida]) problemas.push(`a política não tem a diretiva ${exigida}`)
  }
  if ((diretivas['script-src'] ?? []).some((v) => v === "'unsafe-inline'" || v === "'unsafe-eval'")) problemas.push("script-src não pode ter 'unsafe-inline' nem 'unsafe-eval'")
  // Se o build conhece o endereço da API (na Vercel, VITE_API_URL), a política precisa deixá-lo falar com ela: senão TODA chamada seria bloqueada em silêncio.
  const api = (process.env.VITE_API_URL ?? '').trim()
  if (api) {
    const origem = new URL(api).origin
    if (!(diretivas['connect-src'] ?? []).includes(origem)) problemas.push(`connect-src não inclui a origem da API (${origem}): o site não conseguiria chamá-la`)
  }
}
const html = readFileSync(join(dist, 'index.html'), 'utf8')
if (/<script(?![^>]*\bsrc=)[^>]*>/i.test(html)) problemas.push('dist/index.html tem <script> em linha: a política (script-src) o bloquearia em produção; mova para um arquivo')
if (/\son[a-z]+\s*=\s*["']/i.test(html)) problemas.push('dist/index.html tem atributo de evento em linha (onclick etc.): a política o bloquearia')
if (problemas.length > 0) {
  console.error('Problemas com a política de segurança do site (ADR 0038):\n' + problemas.join('\n'))
  process.exit(1)
}
console.log('política de segurança: presente, sem script em linha e coerente com a API')
