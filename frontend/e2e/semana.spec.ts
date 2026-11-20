import { expect, test, type Page } from '@playwright/test'
import { entrar, irPeloMenu } from './support'

/**
 * A semana da agenda (ADR 0018 v2) em navegador real: sete colunas quando a ÁREA DE CONTEÚDO tem 880 px ou mais (consulta de contêiner,
 * não a largura da janela); abaixo disso, uma seção por dia. Sem rolagem horizontal de 320 a 1280 px.
 */
async function abrirSemana(page: Page, largura: number) {
  await page.setViewportSize({ width: largura, height: 900 })
  await entrar(page, 'prestador')
  await irPeloMenu(page, 'Agenda', largura)
  await page.getByRole('button', { name: 'Semana' }).click()
  await page.getByRole('heading', { level: 2, name: /^Semana de / }).waitFor()
  await page.waitForLoadState('networkidle')
}

const modo = (page: Page) => page.evaluate(() => getComputedStyle(document.querySelector('.week-cols')!).display)

test('1280 px: a grade (coluna das horas e sete dias), com título', async ({ page }) => {
  await abrirSemana(page, 1280)
  expect(await modo(page)).toBe('grid') // a grade: a coluna das horas e os sete dias
  await expect(page.locator('.week-day')).toHaveCount(7)
  await expect(page.locator('.week-day-title').first()).toContainText('Seg')
  await page.screenshot({ path: 'e2e/saida/semana-1280.png', fullPage: true })
})

for (const largura of [1024, 768, 390]) {
  test(`${largura} px: uma seção por dia (a área de conteúdo é menor que 880 px)`, async ({ page }) => {
    await abrirSemana(page, largura)
    expect(await modo(page)).toBe('block') // uma seção por dia, empilhada
    await expect(page.locator('.week-day')).toHaveCount(7)
    await page.screenshot({ path: `e2e/saida/semana-${largura}.png`, fullPage: true })
  })
}

test('sem rolagem horizontal de 320 a 1280 px', async ({ page }) => {
  await abrirSemana(page, 1280)
  for (const largura of [1280, 1024, 768, 390, 320]) {
    await page.setViewportSize({ width: largura, height: 900 })
    await page.waitForTimeout(150)
    const estoura = await page.evaluate(() => document.documentElement.scrollWidth > window.innerWidth + 1)
    expect(estoura, `rolagem horizontal a ${largura} px`).toBe(false)
  }
})
