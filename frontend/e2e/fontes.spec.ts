import { expect, test, type Browser } from '@playwright/test'

/**
 * Estabilidade tipográfica (ADR 0018, seção 2; revisão do R2): a troca da fonte de reserva pela real não pode deslocar o layout.
 * Mede a geometria (largura e altura) de um horário do Fio, de um botão de horário, de um título e de um texto corrido COM as fontes
 * bloqueadas (só a reserva com métricas ajustadas) e COM as fontes carregadas, na página de demonstração. Teste de controle: sem os
 * ajustes (Arial pura) o deslocamento passa do limite, o que prova que a medida enxerga o problema.
 */
const URL = '/__ui'
const ALVOS = {
  'horário do fio': '.pp-fio__time',
  'botão de horário': '.pp-slot:not(:disabled)',
  título: 'h1',
  'texto corrido': '.pp-fio__item p',
} as const
const LIMITE_LARGURA = 0.015 // 1,5 % da largura do elemento (invisível a olho nu; texto de reserva nunca é idêntico à fonte real)
const LIMITE_ALTURA_PX = 3

async function medir(browser: Browser, bloquearFontes: boolean, semAjuste = false) {
  const ctx = await browser.newContext({ viewport: { width: 1280, height: 800 }, locale: 'pt-BR' })
  const page = await ctx.newPage()
  if (bloquearFontes) await page.route(/\.woff2(\?.*)?$/, (r) => r.abort())
  await page.goto(URL, { waitUntil: bloquearFontes ? 'domcontentloaded' : 'networkidle' })
  await page.getByRole('heading', { level: 1, name: 'Fundação visual' }).waitFor()
  if (!bloquearFontes) await page.evaluate(() => document.fonts.ready)
  if (semAjuste) {
    await page.addStyleTag({ content: ".pp{--pp-display:Arial,sans-serif;--pp-body:Arial,sans-serif}" })
  }
  await page.waitForTimeout(300)
  const caixas: Record<string, { w: number; h: number }> = {}
  for (const [nome, seletor] of Object.entries(ALVOS)) {
    caixas[nome] = await page.locator(seletor).first().evaluate((el) => {
      // Largura do TEXTO (o elemento pode ser um bloco de largura fixa): retângulo do conteúdo; altura do próprio elemento.
      const range = document.createRange()
      range.selectNodeContents(el)
      return { w: range.getBoundingClientRect().width, h: el.getBoundingClientRect().height }
    })
  }
  await ctx.close()
  return caixas
}

test('a troca da fonte de reserva pela real desloca no máximo 1,5 % da largura e 3 px da altura', async ({ browser }) => {
  const reserva = await medir(browser, true)
  const real = await medir(browser, false)
  const desvios = Object.keys(ALVOS).map((nome) => ({ nome, dw: Math.abs(reserva[nome].w - real[nome].w) / real[nome].w, dh: Math.abs(reserva[nome].h - real[nome].h) }))
  console.table(desvios.map((d) => ({ nome: d.nome, "largura %": +(d.dw * 100).toFixed(2), "altura px": +d.dh.toFixed(1) })))
  for (const d of desvios) {
    expect(d.dw, `largura de "${d.nome}"`).toBeLessThanOrEqual(LIMITE_LARGURA)
    expect(d.dh, `altura de "${d.nome}"`).toBeLessThanOrEqual(LIMITE_ALTURA_PX)
  }
})

test('controle: com Arial pura (sem os ajustes) o deslocamento passa do limite em pelo menos um alvo', async ({ browser }) => {
  const semAjuste = await medir(browser, true, true)
  const real = await medir(browser, false)
  const estoura = Object.keys(ALVOS).some((n) => Math.abs(semAjuste[n].w - real[n].w) / real[n].w > LIMITE_LARGURA || Math.abs(semAjuste[n].h - real[n].h) > LIMITE_ALTURA_PX)
  expect(estoura).toBe(true)
})
