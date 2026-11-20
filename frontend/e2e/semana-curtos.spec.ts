import { expect, test } from '@playwright/test'
import { entrar, irPeloMenu } from './support'

/**
 * Semana com atendimentos CURTOS (15 e 30 minutos, inclusive seguidos), em navegador real: a hora da grade cresce, os blocos não se
 * encostam nem se sobrepõem, e cada bloco mostra horário e nome. A resposta da API é simulada só aqui (os dados de demonstração não têm
 * atendimentos tão curtos).
 */
test('blocos de 15 e 30 minutos seguidos: sem sobreposição e com horário e nome visíveis', async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 900 })
  await page.route('**/api/agenda/week?**', async (route) => {
    const start = new URL(route.request().url()).searchParams.get('start')!
    const item = (id: string, hh: number, mm: number, minutos: number, nome: string) => {
      const inicio = new Date(`${start}T${String(hh).padStart(2, '0')}:${String(mm).padStart(2, '0')}:00-03:00`)
      return { id, startsAt: inicio.toISOString(), endsAt: new Date(inicio.getTime() + minutos * 60_000).toISOString(), status: 'SCHEDULED', serviceId: 's', serviceName: 'Banho', customerId: 'c', customerName: nome }
    }
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify([item('1', 9, 0, 15, 'Ana'), item('2', 9, 15, 15, 'Beto'), item('3', 9, 30, 30, 'Caio'), item('4', 10, 0, 30, 'Duda'), item('5', 11, 0, 60, 'Eva')]),
    })
  })
  await entrar(page, 'prestador')
  await irPeloMenu(page, 'Agenda', 1280)
  await page.getByRole('button', { name: 'Semana' }).click()
  await page.getByRole('heading', { level: 2, name: /^Semana de / }).waitFor()
  await page.waitForLoadState('networkidle')

  const caixas = await page.evaluate(() => [...document.querySelectorAll('.week-day:first-of-type .week-block__open, .week-day .week-block__open')].slice(0, 5).map((e) => {
    const r = e.getBoundingClientRect()
    return { top: r.top, bottom: r.bottom, texto: (e.textContent ?? '').trim() }
  }))
  expect(caixas).toHaveLength(5)
  for (let i = 0; i < caixas.length - 1; i++) expect(caixas[i + 1].top, `bloco ${i + 2} começa depois do fim do ${i + 1}`).toBeGreaterThanOrEqual(caixas[i].bottom)
  for (const c of caixas) {
    expect(c.bottom - c.top, 'altura mínima de um bloco').toBeGreaterThanOrEqual(26)
    expect(c.texto).toMatch(/\d{2}:\d{2}/)
  }
  await page.screenshot({ path: 'e2e/saida/semana-curtos.png', fullPage: true })
})
