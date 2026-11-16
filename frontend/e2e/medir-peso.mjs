// Mede o PESO REAL TRANSFERIDO por rota, em build de produção (ADR 0018, seção 8, item 7; observação do revisor para o R1):
// não confunde o tamanho total do build com o que a primeira visita carrega. Tamanho = gzip -9 do corpo de cada resposta
// (conservador: a hospedagem serve Brotli, que é menor), por tipo: JS, CSS, fontes e o restante. Uso: `npm run e2e:peso`.
// Rotas: /entrar (primeira visita) e, depois de entrar, as telas mais pesadas, contando SÓ o que chega a mais (código carregado sob demanda).
import { chromium } from '@playwright/test'
import { spawn, spawnSync } from 'node:child_process'
import { mkdirSync, writeFileSync } from 'node:fs'
import { join, resolve } from 'node:path'
import { gzipSync } from 'node:zlib'

const raiz = resolve(import.meta.dirname, '..')
const saida = join(raiz, 'e2e', 'saida')
const PASSWORD = ['Senha', 'Demo', '12345'].join('-')
const npm = process.platform === 'win32' ? 'npm.cmd' : 'npm'

const build = spawnSync(npm, ['run', 'build'], { cwd: raiz, stdio: 'inherit', shell: process.platform === 'win32' })
if (build.status !== 0) process.exit(build.status ?? 1)

const preview = spawn(npm, ['run', 'preview', '--', '--host', '127.0.0.1', '--port', '4173', '--strictPort'], { cwd: raiz, stdio: 'ignore', shell: process.platform === 'win32' })
const esperar = async (url) => {
  for (let i = 0; i < 60; i++) {
    try { if ((await fetch(url)).ok) return } catch { /* ainda subindo */ }
    await new Promise((r) => setTimeout(r, 500))
  }
  throw new Error('servidor de pré-visualização não subiu')
}

const tipo = (url, contentType) => (/\.(woff2?|ttf)$/.test(url) || /font/.test(contentType) ? 'fontes' : /javascript/.test(contentType) || /\.js$/.test(url) ? 'js' : /css/.test(contentType) || /\.css$/.test(url) ? 'css' : 'outros')

async function medir(page, nome, acao) {
  const total = { js: 0, css: 0, fontes: 0, outros: 0 }
  const arquivos = []
  const aoResponder = async (r) => {
    try {
      const url = new URL(r.url())
      if (url.pathname.startsWith('/api/')) return
      const corpo = await r.body()
      const tam = gzipSync(corpo, { level: 9 }).length
      const t = tipo(url.pathname, r.headers()['content-type'] ?? '')
      total[t] += tam
      arquivos.push({ arquivo: url.pathname, tipo: t, gzipBytes: tam })
    } catch { /* resposta sem corpo (redirecionamento) */ }
  }
  page.on('response', aoResponder)
  await acao()
  await page.waitForLoadState('networkidle')
  page.off('response', aoResponder)
  return { rota: nome, ...total, totalBytes: total.js + total.css + total.fontes + total.outros, arquivos }
}

try {
  await esperar('http://127.0.0.1:4173/')
  const browser = await chromium.launch({ channel: 'msedge' })
  const page = await (await browser.newContext({ locale: 'pt-BR', viewport: { width: 1280, height: 800 } })).newPage()
  const resultados = []
  resultados.push(await medir(page, '/entrar (primeira visita)', () => page.goto('http://127.0.0.1:4173/entrar')))
  await page.getByLabel('E-mail').fill('helena@pelo.test')
  await page.getByLabel('Senha').fill(PASSWORD)
  resultados.push(await medir(page, 'depois de entrar (início do prestador), só o que chega a mais', () => page.getByRole('button', { name: 'Entrar' }).click()))
  resultados.push(await medir(page, '/agenda, só o que chega a mais', () => page.getByRole('navigation').getByRole('link', { name: 'Agenda', exact: true }).first().click()))
  await browser.close()
  mkdirSync(saida, { recursive: true })
  writeFileSync(join(saida, 'peso.json'), JSON.stringify(resultados, null, 2))
  const kb = (n) => (n / 1000).toFixed(1).padStart(7) + ' kB'
  console.log('\nPeso transferido (gzip -9, em kB):\n')
  console.log('rota'.padEnd(66), '      JS      CSS    fontes   outros     total')
  for (const r of resultados) console.log(r.rota.padEnd(66), kb(r.js), kb(r.css), kb(r.fontes), kb(r.outros), kb(r.totalBytes))
} finally {
  preview.kill()
}
