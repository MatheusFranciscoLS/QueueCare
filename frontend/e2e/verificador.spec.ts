import { expect, test } from '@playwright/test'
import { alvosPequenos, checarFoco } from './medidas'

/**
 * Teste do PRÓPRIO verificador (revisão do R1): uma medida que não reprova o que deve reprovar não serve de critério para os PRs de tela.
 * Páginas mínimas montadas na hora, com casos que devem passar e casos que devem falhar. Roda sem a API: `npx playwright test e2e/verificador`.
 */
const base = (corpo: string, css = '') =>
  `<!doctype html><html lang="pt-BR"><meta charset="utf-8"><style>
    body{font:16px sans-serif;margin:24px;background:#fff;color:#111} .linha{margin:0 0 28px} ${css}
  </style><body>${corpo}</body></html>`

test.describe('foco visível, medido pelos pixels', () => {
  test('aprova anel visível e reprova ausência, sombra transparente e contorno sem contraste', async ({ page }) => {
    await page.setContent(
      base(
        `<div class="linha"><button id="bom">Com anel</button></div>
         <div class="linha"><button id="nenhum">Sem indicador</button></div>
         <div class="linha"><button id="transparente">Sombra transparente</button></div>
         <div class="linha"><button id="fraco">Contorno quase invisível</button></div>
         <div class="linha"><button id="fino">Contorno de 1 px</button></div>`,
        `button{padding:10px 16px;border:1px solid #888;background:#eee}
         #bom:focus{outline:3px solid #0b57d0;outline-offset:2px}
         #nenhum:focus{outline:none}
         #transparente:focus{outline:none;box-shadow:0 0 0 4px rgba(0,0,0,0)}
         #fraco:focus{outline:3px solid #fefefe;outline-offset:2px}
         #fino:focus{outline:1px solid #0b57d0;outline-offset:0}`,
      ),
    )
    const r = await checarFoco(page)
    expect(r.total).toBe(5)
    const reprovados = r.sem.map((s) => s.split(' (')[0])
    expect(reprovados).toEqual(['button "Sem indicador"', 'button "Sombra transparente"', 'button "Contorno quase invisível"', 'button "Contorno de 1 px"'])
  })

  test('campo de data: anel aprova, ausência reprova, e todos os pedaços do campo contam como um só elemento', async ({ page }) => {
    await page.setContent(
      base(
        `<div class="linha"><input id="com" type="date" aria-label="com anel"></div>
         <div class="linha"><input id="sem" type="date" aria-label="sem anel"></div>`,
        `input{padding:8px;border:1px solid #888}
         #com:focus-within{outline:3px solid #0b57d0;outline-offset:2px}
         #sem:focus-within{outline:none}
         #sem::-webkit-datetime-edit-day-field:focus,#sem::-webkit-datetime-edit-month-field:focus,#sem::-webkit-datetime-edit-year-field:focus{background:none;color:inherit}`,
      ),
    )
    const r = await checarFoco(page)
    expect(r.total).toBe(2)
    expect(r.sem.map((s) => s.split(' (')[0])).toEqual(['input "sem anel"'])
  })
})

test.describe('alvos de toque', () => {
  test('mede largura e altura e conta a área do rótulo em caixas de marcar', async ({ page }) => {
    await page.setContent(
      base(
        `<button id="ok" style="width:44px;height:44px">ok</button>
         <button id="baixo" style="width:80px;height:40px">baixo</button>
         <button id="estreito" style="width:30px;height:60px">x</button>
         <label style="display:flex;align-items:center;gap:8px;min-height:48px;min-width:120px"><input type="checkbox" style="width:18px;height:18px"> rótulo grande</label>
         <div><input type="checkbox" id="solto" aria-label="marcador solto" style="width:18px;height:18px"></div>
         <input type="radio" name="g" id="radio-solto" aria-label="opção solta" style="width:18px;height:18px">`,
      ),
    )
    const pequenos = await alvosPequenos(page)
    expect(pequenos).toEqual(['button "baixo"', 'button "x"', 'input "marcador solto"', 'input "opção solta"'])
  })
})
