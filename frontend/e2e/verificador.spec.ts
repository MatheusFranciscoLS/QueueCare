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
         <div class="linha"><button id="fino">Contorno de 1 px</button></div>
         <div class="linha"><button id="solto">Dois fios separados</button></div>
         <div class="linha"><button id="fundo">Só o fundo muda</button></div>
         <div class="linha"><button id="topo">Anel só em cima</button></div>
         <div class="linha"><button id="interno">Anel por dentro</button></div>
         <div class="linha"><button id="pilula">Botão em pílula</button></div>`,
        `button{padding:10px 16px;border:1px solid #888;background:#eee}
         #bom:focus{outline:3px solid #0b57d0;outline-offset:2px}
         #nenhum:focus{outline:none}
         #transparente:focus{outline:none;box-shadow:0 0 0 4px rgba(0,0,0,0)}
         #fraco:focus{outline:3px solid #fefefe;outline-offset:2px}
         #fino:focus{outline:1px solid #0b57d0;outline-offset:0}
         #solto:focus{outline:1px solid #0b57d0;outline-offset:4px;box-shadow:0 0 0 1px #0b57d0}
         #fundo:focus{outline:none;background:#0b57d0;color:#fff}
         #topo:focus{outline:none;box-shadow:0 -3px 0 0 #0b57d0}
         #interno:focus{outline:3px solid #0b57d0;outline-offset:-4px}
         #pilula{border-radius:999px;padding:10px 28px}
         #pilula:focus{outline:3px solid #0b57d0;outline-offset:2px}`,
      ),
    )
    const r = await checarFoco(page)
    expect(r.total).toBe(10)
    const reprovados = r.sem.map((s) => s.split(' (')[0])
    expect(reprovados).toEqual([
      'button "Sem indicador"',
      'button "Sombra transparente"',
      'button "Contorno quase invisível"',
      'button "Contorno de 1 px"',
      'button "Dois fios separados"', // dois pixels soltos de espessura, sem contiguidade: não é um anel de 2 px
      'button "Só o fundo muda"', // mudança de fundo, sem anel: não é indicador de foco
      'button "Anel só em cima"', // um lado só: o anel precisa cercar o elemento
    ])
    expect(r.sem.find((m) => m.includes('Só o fundo muda'))).toContain('fundo inteiro muda')
  })

  test('elemento colado na borda da janela reprova (lado não mensurável); elemento longe, abaixo da dobra, é rolado até o centro e aprova', async ({ page }) => {
    await page.setContent(
      base(
        `<button id="colado" style="position:fixed;left:0;top:300px">Colado na borda</button>
         <div style="height:1600px"></div>
         <button id="longe">Abaixo da dobra</button><div style="height:900px"></div>`,
        `button{padding:10px 16px;border:1px solid #888;background:#eee}
         button:focus{outline:3px solid #0b57d0;outline-offset:2px}`,
      ),
    )
    const r = await checarFoco(page)
    expect(r.sem.map((m) => m.split(' (')[0])).toEqual(['button "Colado na borda"'])
    expect(r.sem[0]).toContain('de 4 lados')
  })

  test('elemento que só aparece ao receber o foco (atalho) aprova', async ({ page }) => {
    await page.setContent(
      base(
        `<a id="atalho" href="#fim" style="position:absolute;left:8px;top:-80px;padding:12px 16px;background:#14202b;color:#fff">Pular para o conteúdo</a>
         <button id="depois" style="margin-top:200px">Botão comum</button>`,
        `a:focus{top:8px!important} button{padding:10px 16px;border:1px solid #888;background:#eee} button:focus{outline:3px solid #0b57d0;outline-offset:2px}`,
      ),
    )
    const r = await checarFoco(page)
    expect(r.total).toBe(2)
    expect(r.sem).toEqual([])
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
         <input type="radio" name="g" id="radio-solto" aria-label="opção solta" style="width:18px;height:18px">
         <p><a href="#x" style="display:inline-block;height:20px">link no texto</a> <a href="#y" style="display:inline-block;width:60px;height:48px">link ok</a></p>`,
      ),
    )
    const pequenos = await alvosPequenos(page)
    expect(pequenos).toEqual(['button "baixo"', 'button "x"', 'input "marcador solto"', 'input "opção solta"', 'a "link no texto"'])
  })
})
