// Gera os ícones PNG do app (PWA) a partir de public/icon.svg, usando o próprio navegador (sem dependência nova).
// Uso: node scripts/gerar-icones.mjs   (precisa do Edge ou do Chromium do Playwright; os PNGs são commitados)
import { chromium } from '@playwright/test'
import { readFileSync } from 'node:fs'
import { join, resolve } from 'node:path'

const pub = resolve(import.meta.dirname, '..', 'public')
const svg = readFileSync(join(pub, 'icon.svg'), 'utf8')

/** Ícone "mascarável": fundo até a borda (o sistema recorta em círculo, quadrado arredondado etc.) e o desenho dentro dos 60% centrais. */
const maskable = svg
  .replace('rx="112"', 'rx="0"')
  .replace('font-size="300"', 'font-size="230"')
  .replace('y="352"', 'y="334"')

const alvos = [
  { arquivo: 'icon-192.png', tamanho: 192, fonte: svg },
  { arquivo: 'icon-512.png', tamanho: 512, fonte: svg },
  { arquivo: 'icon-maskable-512.png', tamanho: 512, fonte: maskable },
  { arquivo: 'apple-touch-icon.png', tamanho: 180, fonte: maskable }, // o iOS aplica o próprio recorte: fundo cheio
]

const browser = await chromium.launch({ channel: 'msedge' }).catch(() => chromium.launch())
try {
  for (const { arquivo, tamanho, fonte } of alvos) {
    const page = await browser.newPage({ viewport: { width: tamanho, height: tamanho }, deviceScaleFactor: 1 })
    await page.setContent(`<html><body style="margin:0;background:transparent">${fonte.replace('<svg ', `<svg width="${tamanho}" height="${tamanho}" `)}</body></html>`)
    await page.screenshot({ path: join(pub, arquivo), omitBackground: true, clip: { x: 0, y: 0, width: tamanho, height: tamanho } })
    await page.close()
    console.log('gerado', arquivo)
  }
} finally {
  await browser.close()
}
