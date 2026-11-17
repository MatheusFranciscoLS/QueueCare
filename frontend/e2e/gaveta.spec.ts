import { expect, test } from '@playwright/test'
import { entrar } from './support'

/**
 * Ciclo de foco da gaveta do celular, em navegador real (ADR 0018, seção 4): foco inicial dentro dela, foco contido (Tab e Shift+Tab não saem),
 * fundo inerte, Esc fecha e devolve o foco ao botão Menu, e escolher um item fecha a gaveta e leva o foco ao título da nova tela.
 */
test.use({ viewport: { width: 390, height: 844 } })

test('gaveta: foco inicial, foco contido, fundo inerte, Esc e retorno do foco', async ({ page }) => {
  await entrar(page, 'cliente')
  const menu = page.getByRole('button', { name: 'Menu' })
  await menu.focus()
  await page.keyboard.press('Enter')
  const gaveta = page.getByRole('dialog', { name: 'Menu' })
  await expect(gaveta).toBeVisible()

  const dentro = () => page.evaluate(() => !!document.activeElement?.closest('dialog.pp-drawer'))
  // Foco CONTIDO: dentro da gaveta ou fora do documento (o navegador leva o Tab seguinte à barra de endereço e de volta); nunca no conteúdo por trás.
  const contido = () => page.evaluate(() => { const a = document.activeElement; return !!a?.closest('dialog.pp-drawer') || a === document.body || a === null })
  expect(await dentro(), 'foco inicial dentro da gaveta').toBe(true)

  // Tab além de todos os itens e Shift+Tab: o foco nunca sai da gaveta.
  for (let i = 0; i < 14; i++) {
    await page.keyboard.press('Tab')
    expect(await contido(), `Tab ${i + 1} contido na gaveta`).toBe(true)
  }
  for (let i = 0; i < 14; i++) {
    await page.keyboard.press('Shift+Tab')
    expect(await contido(), `Shift+Tab ${i + 1} contido na gaveta`).toBe(true)
  }

  // Fundo inerte: o botão do menu e o conteúdo não recebem clique nem foco enquanto a gaveta está aberta.
  const inerte = await page.evaluate(() => {
    const main = document.querySelector('main')!
    const alvo = document.elementFromPoint(380, 400)
    return { mainClicavel: alvo === main || main.contains(alvo) }
  })
  expect(inerte.mainClicavel, 'o conteúdo não é clicável por trás da gaveta').toBe(false)

  await page.keyboard.press('Escape')
  await expect(gaveta).toBeHidden()
  await expect(menu, 'o foco volta ao botão que abriu a gaveta').toBeFocused()
})

test('gaveta: escolher um item fecha a gaveta e leva o foco ao título da nova tela', async ({ page }) => {
  await entrar(page, 'cliente')
  await page.getByRole('button', { name: 'Menu' }).click()
  await page.getByRole('dialog', { name: 'Menu' }).getByRole('link', { name: 'Meus pets' }).click()
  await expect(page.getByRole('dialog')).toBeHidden()
  const titulo = page.getByRole('heading', { level: 1, name: 'Meus pets' })
  await expect(titulo).toBeFocused()
  await expect(page).toHaveTitle('Meus pets · Pelo & Ponto')
})
