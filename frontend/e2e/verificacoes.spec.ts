import AxeBuilder from '@axe-core/playwright'
import { expect, test, type Page } from '@playwright/test'
import { LARGURAS_ROLAGEM, TELAS, TEMAS, VIEWPORTS, abrirTela, gravarJson } from './support'

/**
 * Verificações MENSURÁVEIS em navegador real (ADR 0018, seção 8). Por tela e tema:
 *  3. axe-core (WCAG 2.2 AA): violações graves e críticas;
 *  4. teclado: percurso por Tab e foco VISÍVEL em cada elemento interativo;
 *  6. sem rolagem horizontal em 320, 390, 768, 1024 e 1280 px; alvos de toque de pelo menos 44 px.
 * Modo padrão = BASELINE: mede e grava e2e/saida/relatorio.json sem reprovar (o produto atual ainda não cumpre). Com QC_STRICT=1 cada
 * violação reprova o teste: é como cada PR de tela do redesign é conferido. Filtre com QC_TELAS=agenda,fila.
 */
const ESTRITO = process.env.QC_STRICT === '1'
const filtro = process.env.QC_TELAS?.split(',').map((s) => s.trim()).filter(Boolean)
const telas = filtro ? TELAS.filter((t) => filtro.some((f) => t.id.includes(f))) : TELAS

interface Achado {
  tela: string
  tema: string
  janela: string
  axeGraves: { regra: string; impacto: string; nos: number; exemplo: string }[]
  focoSemContorno: string[]
  semFoco: number
  rolagemHorizontal: number[]
  alvosPequenos: string[]
}
const achados: Achado[] = []

const descricao = `(el) => {
  const tag = el.tagName.toLowerCase()
  const nome = (el.getAttribute('aria-label') || el.textContent || el.getAttribute('name') || '').trim().slice(0, 40)
  return tag + (nome ? ' "' + nome + '"' : '')
}`

/** Percorre a página com Tab e confere se cada elemento focado tem contorno visível (outline com largura, ou sombra). */
async function checarFoco(page: Page): Promise<{ sem: string[]; total: number }> {
  const sem: string[] = []
  let total = 0
  await page.evaluate(() => (document.activeElement as HTMLElement | null)?.blur())
  for (let i = 0; i < 80; i++) {
    await page.keyboard.press('Tab')
    const r = await page.evaluate(`(() => {
      const el = document.activeElement
      if (!el || el === document.body) return null
      const cs = getComputedStyle(el)
      const outline = cs.outlineStyle !== 'none' && parseFloat(cs.outlineWidth) >= 2
      const sombra = cs.boxShadow !== 'none'
      const descrever = ${descricao}
      return { visivel: outline || sombra, nome: descrever(el), id: el.tagName + (el.id || '') + (el.textContent || '').slice(0, 20) }
    })()`) as { visivel: boolean; nome: string; id: string } | null
    if (!r) break
    total += 1
    if (!r.visivel) sem.push(r.nome)
  }
  return { sem, total }
}

async function alvosPequenos(page: Page): Promise<string[]> {
  return (await page.evaluate(`(() => {
    const descrever = ${descricao}
    const itens = [...document.querySelectorAll('button, [role=button], input:not([type=checkbox]):not([type=radio]):not([type=hidden]), select, textarea, nav a, a.button')]
    return itens.filter((el) => { const r = el.getBoundingClientRect(); return r.width > 0 && r.height > 0 && r.height < 44 }).map(descrever)
  })()`)) as string[]
}

for (const tela of telas) {
  for (const [nomeJanela, viewport] of Object.entries(VIEWPORTS)) {
    for (const tema of TEMAS) {
      test(`verificar ${tela.id} ${nomeJanela} ${tema}`, async ({ page }) => {
        await abrirTela(page, tela, tema, viewport)

        const axe = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag22aa']).analyze()
        const graves = axe.violations.filter((v) => v.impact === 'serious' || v.impact === 'critical')
        const foco = await checarFoco(page)
        const pequenos = await alvosPequenos(page)
        const rolagem: number[] = []
        for (const largura of LARGURAS_ROLAGEM) {
          await page.setViewportSize({ width: largura, height: viewport.height })
          await page.waitForTimeout(150)
          const estoura = await page.evaluate(() => document.documentElement.scrollWidth > window.innerWidth + 1)
          if (estoura) rolagem.push(largura)
        }
        await page.setViewportSize(viewport)

        achados.push({
          tela: tela.id,
          tema,
          janela: nomeJanela,
          axeGraves: graves.map((v) => ({ regra: v.id, impacto: String(v.impact), nos: v.nodes.length, exemplo: v.nodes[0]?.html.slice(0, 120) ?? '' })),
          focoSemContorno: foco.sem,
          semFoco: foco.total,
          rolagemHorizontal: rolagem,
          alvosPequenos: pequenos,
        })

        if (ESTRITO) {
          expect(graves.map((v) => v.id), 'violações graves ou críticas do axe').toEqual([])
          expect(foco.sem, 'elementos sem foco visível').toEqual([])
          expect(rolagem, 'larguras com rolagem horizontal').toEqual([])
          expect(pequenos, 'alvos de toque menores que 44 px').toEqual([])
        }
      })
    }
  }
}

test.afterAll(() => {
  gravarJson('relatorio.json', achados)
  const soma = (f: (a: Achado) => number) => achados.reduce((n, a) => n + f(a), 0)
  console.log(
    `\n[verificações] ${achados.length} combinações | axe graves/críticas: ${soma((a) => a.axeGraves.length)} | focos sem contorno: ${soma((a) => a.focoSemContorno.length)} | ` +
      `larguras com rolagem horizontal: ${soma((a) => a.rolagemHorizontal.length)} | alvos < 44 px: ${soma((a) => a.alvosPequenos.length)}`,
  )
})
