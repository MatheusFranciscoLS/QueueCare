// Mede as métricas das fontes de reserva (ADR 0018, seção 2): para cada fonte da interface, quanto a fonte de reserva precisa ser
// escalada (size-adjust) e deslocada (ascent/descent-override) para ocupar o mesmo espaço. Usa a página de demonstração (as fontes
// reais carregadas) e o canvas do navegador. Uso: com o Vite no ar, `node e2e/medir-metricas.mjs`.
import { chromium } from '@playwright/test'

const URL = process.env.QC_URL ?? 'http://127.0.0.1:5173/__ui'
const browser = await chromium.launch({ channel: 'msedge' })
const page = await (await browser.newContext({ viewport: { width: 1280, height: 800 } })).newPage()
await page.goto(URL, { waitUntil: 'networkidle' })
await page.evaluate(() => document.fonts.ready)

const AMOSTRA_TITULO = 'Fundação visual Pelo e Ponto Agenda Concluído Marina Alves'
const casos = [
  ...[16, 18].map((px) => ({ nome: 'digitos 400 a ' + px + 'px', alvo: '400 ' + px + 'px "Bricolage Grotesque Variable"', reserva: '400 ' + px + 'px Arial', amostra: '0123456789:1 h 30 45 min 08:00 20/11/2026' })),
  ...[16].map((px) => ({ nome: 'digitos 500 a ' + px + 'px', alvo: '500 ' + px + 'px "Bricolage Grotesque Variable"', reserva: '500 ' + px + 'px Arial', amostra: '0123456789:1 h 30 45 min 08:00 20/11/2026' })),
  ...[17, 22, 30, 44].map((px) => ({ nome: 'digitos 600 a ' + px + 'px', alvo: '600 ' + px + 'px "Bricolage Grotesque Variable"', reserva: '600 ' + px + 'px Arial', amostra: '0123456789:08:30 10:45 1 h 30' })),
  ...[22, 30, 44].map((px) => ({ nome: 'titulos 700 a ' + px + 'px', alvo: '700 ' + px + 'px "Bricolage Grotesque Variable"', reserva: '700 ' + px + 'px Arial', amostra: AMOSTRA_TITULO })),
  { nome: 'texto 400 a 16px', alvo: '400 16px "Atkinson Hyperlegible Next Variable"', reserva: '400 16px Arial', amostra: 'Texto corrido em português, Cão, porte médio, Banho e tosa, Remarcar' },
  { nome: 'texto 700 a 16px', alvo: '700 16px "Atkinson Hyperlegible Next Variable"', reserva: '700 16px Arial', amostra: 'Marcar chegada Começar atendimento' },
]
const resultados = await page.evaluate((casos) => {
  const cv = new OffscreenCanvas(10, 10).getContext('2d')
  const medir = (fonte, texto) => {
    cv.font = fonte
    const m = cv.measureText(texto)
    const px = parseFloat(fonte.split(' ')[1])
    return { w: m.width, asc: m.fontBoundingBoxAscent / px, desc: m.fontBoundingBoxDescent / px }
  }
  return casos.map((c) => {
    const a = medir(c.alvo, c.amostra)
    const r = medir(c.reserva, c.amostra)
    const sizeAdjust = a.w / r.w
    return { nome: c.nome, sizeAdjust: +(sizeAdjust * 100).toFixed(2), ascentOverride: +((a.asc / sizeAdjust) * 100).toFixed(2), descentOverride: +((a.desc / sizeAdjust) * 100).toFixed(2) }
  })
}, casos)
console.table(resultados)
await browser.close()
