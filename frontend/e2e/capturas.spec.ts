import { test } from '@playwright/test'
import { join } from 'node:path'
import { TELAS, TEMAS, VIEWPORTS, abrirTela, pasta } from './support'

/**
 * Capturas de TODAS as telas em 1280 e 390 px, nos temas claro e escuro (ADR 0018, seção 8, item 2): é o que o dono vê e aprova a cada PR.
 * Saída: e2e/saida/capturas/<tela>-<janela>-<tema>.png (não vai para o repositório). Filtre com QC_TELAS=agenda,fila.
 */
const filtro = process.env.QC_TELAS?.split(',').map((s) => s.trim()).filter(Boolean)
const telas = filtro ? TELAS.filter((t) => filtro.some((f) => t.id.includes(f))) : TELAS

for (const tela of telas) {
  for (const [nomeJanela, viewport] of Object.entries(VIEWPORTS)) {
    for (const tema of TEMAS) {
      test(`captura ${tela.id} ${nomeJanela} ${tema}`, async ({ page }) => {
        await abrirTela(page, tela, tema, viewport)
        await page.screenshot({ path: join(pasta('capturas'), `${tela.id}-${nomeJanela}-${tema}.png`), fullPage: true })
      })
    }
  }
}
