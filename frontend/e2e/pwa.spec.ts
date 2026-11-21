import { expect, test } from '@playwright/test'

/**
 * App instalável e offline (ADR 0030), no navegador real e no site de PRODUÇÃO (o service worker só é registrado no build, nunca em desenvolvimento).
 * Roda só com QC_PWA_URL (o endereço do `vite preview`):
 *   npm run build && npx vite preview --port 4173 --strictPort &
 *   QC_PWA_URL=http://localhost:4173 npx playwright test e2e/pwa.spec.ts
 */
const URL_PWA = process.env.QC_PWA_URL
test.skip(!URL_PWA, 'defina QC_PWA_URL (veja o comentário do arquivo)')
test.use({ baseURL: URL_PWA })

test('o service worker assume a página e o app abre SEM internet', async ({ page, context }) => {
  await page.goto('/entrar')
  await page.getByRole('heading', { level: 1, name: 'Entrar' }).waitFor()

  // O worker registra, ativa e passa a controlar a página.
  await page.evaluate(() => navigator.serviceWorker.ready)
  await page.waitForFunction(() => navigator.serviceWorker.controller !== null)

  // Recarrega uma vez para que os scripts com hash passem pelo worker e fiquem guardados.
  await page.reload()
  await page.getByRole('heading', { level: 1, name: 'Entrar' }).waitFor()

  await context.setOffline(true)
  await page.reload()
  await page.getByRole('heading', { level: 1, name: 'Entrar' }).waitFor() // o app abriu sem rede
  await expect(page.getByRole('status').filter({ hasText: 'Sem internet' })).toBeVisible() // e avisa que está offline

  // Entrar offline mostra a mensagem de sempre, em vez de quebrar.
  await page.getByLabel('E-mail').fill('x@exemplo.test')
  await page.getByLabel('Senha').fill('uma-senha-qualquer')
  await page.getByRole('button', { name: 'Entrar' }).click()
  await expect(page.getByRole('alert')).toContainText(/Sem conexão/)

  await context.setOffline(false)
  await expect(page.getByRole('status').filter({ hasText: 'Sem internet' })).toHaveCount(0) // voltou: o aviso some
})

test('o worker nunca guarda chamadas da API', async ({ page }) => {
  await page.goto('/entrar')
  await page.evaluate(() => navigator.serviceWorker.ready)
  const guardados = await page.evaluate(async () => {
    const urls: string[] = []
    for (const nome of await caches.keys()) for (const req of await (await caches.open(nome)).keys()) urls.push(new URL(req.url).pathname)
    return urls
  })
  expect(guardados.length).toBeGreaterThan(0)
  expect(guardados.filter((u) => u.startsWith('/api/') || u.startsWith('/actuator/'))).toEqual([])
})

test('o Chrome considera o app instalável (manifesto, ícones e service worker)', async ({ page }) => {
  await page.goto('/entrar')
  await page.evaluate(() => navigator.serviceWorker.ready)
  const client = await page.context().newCDPSession(page)
  const manifest = await client.send('Page.getAppManifest')
  expect(manifest.errors).toEqual([])
  expect(JSON.parse(manifest.data ?? '{}').name).toContain('Pelo & Ponto')
  const { installabilityErrors } = await client.send('Page.getInstallabilityErrors')
  // O Playwright roda em contexto anônimo, onde o Chrome nunca oferece instalar ("in-incognito"): isso é do ambiente. Qualquer OUTRO motivo seria falha do app.
  const doApp = installabilityErrors.filter((e) => e.errorId !== 'in-incognito')
  expect(doApp, JSON.stringify(doApp)).toEqual([])
})
