import type { Page } from '@playwright/test'

// Medidas reutilizáveis da verificação em navegador real (ADR 0018, seção 8): foco visível (pixels) e alvos de toque.
// Têm teste próprio em verificador.spec.ts: uma medida que não reprova o que deve reprovar não serve de critério.

const descricao = `(el) => {
  const tag = el.tagName.toLowerCase()
  const nome = (el.getAttribute('aria-label') || el.textContent || el.getAttribute('name') || '').trim().slice(0, 40)
  return tag + (nome ? ' "' + nome + '"' : '')
}`

/**
 * Foco VISÍVEL, medido pelos PIXELS e não pelas propriedades de CSS (uma sombra transparente ou um contorno sem contraste passariam
 * numa checagem de propriedades). Para cada elemento que recebe foco por Tab: fotografa a região dele (com uma margem) COM foco e SEM foco
 * e compara pixel a pixel. Conta como indicador o pixel que mudou e tem contraste de pelo menos 3:1 contra o que havia antes
 * (WCAG 2.4.13: mudança de contraste de 3:1; área de pelo menos um anel de 2 px ao redor do elemento).
 */
const MARGEM_FOCO = 6
const CONTRASTE_MINIMO = 3
const ESPESSURA_MINIMA = 2

/** Roda dentro da página: recebe as duas fotos (base64 PNG) e devolve quantos pixels formam o indicador. */
const compararFotos = `async (fotos) => {
  const carregar = async (b64) => {
    const bytes = Uint8Array.from(atob(b64), (c) => c.charCodeAt(0))
    const bmp = await createImageBitmap(new Blob([bytes], { type: 'image/png' }))
    const cv = new OffscreenCanvas(bmp.width, bmp.height)
    const cx = cv.getContext('2d')
    cx.drawImage(bmp, 0, 0)
    return cx.getImageData(0, 0, bmp.width, bmp.height)
  }
  const [com, sem] = [await carregar(fotos.com), await carregar(fotos.sem)]
  const lin = (v) => { const s = v / 255; return s <= 0.03928 ? s / 12.92 : Math.pow((s + 0.055) / 1.055, 2.4) }
  const lum = (d, i) => 0.2126 * lin(d[i]) + 0.7152 * lin(d[i + 1]) + 0.0722 * lin(d[i + 2])
  let pixels = 0
  for (let i = 0; i < com.data.length; i += 4) {
    const a = lum(com.data, i), b = lum(sem.data, i)
    const razao = (Math.max(a, b) + 0.05) / (Math.min(a, b) + 0.05)
    if (razao >= ${CONTRASTE_MINIMO}) pixels += 1
  }
  return { pixels, largura: com.width, altura: com.height }
}`

/** Percorre a página com Tab e confere se cada elemento focado mostra um indicador de foco real, medido pelos pixels. */
export async function checarFoco(page: Page): Promise<{ sem: string[]; total: number }> {
  const sem: string[] = []
  let total = 0
  const janela = page.viewportSize()!
  // Um botão invisível (fora da tela) recebe o foco para TIRAR o foco do elemento medido: blur() não basta em campos de data e hora,
  // cujos pedaços (dia, mês, ano) mantêm o foco por dentro. Ele é removido no fim (não é do produto).
  await page.evaluate(() => {
    const sorvedouro = document.createElement('button')
    sorvedouro.id = '__sorvedouro'
    sorvedouro.tabIndex = -1
    sorvedouro.style.cssText = 'position:fixed;left:-9999px;top:0;width:1px;height:1px;opacity:0'
    document.body.append(sorvedouro)
    ;(document.activeElement as HTMLElement | null)?.blur()
  })
  let repetidosSeguidos = 0
  for (let i = 0; i < 150 && repetidosSeguidos < 12; i++) {
    await page.keyboard.press('Tab')
    const caixa = (await page.evaluate(`(() => {
      const el = document.activeElement
      if (!el || el === document.body) return null
      const vistos = (window.__vistos ??= new WeakSet())
      if (vistos.has(el)) return { repetido: true } // outro pedaço do mesmo campo de data ou hora: já medido
      vistos.add(el)
      el.scrollIntoView({ block: 'nearest', inline: 'nearest' })
      window.__alvoFoco = el
      const r = el.getBoundingClientRect()
      const descrever = ${descricao}
      return { x: r.x, y: r.y, w: r.width, h: r.height, nome: descrever(el) }
    })()`)) as { x: number; y: number; w: number; h: number; nome: string; repetido?: true } | null
    if (!caixa) break // o foco saiu da página: o percurso terminou
    if (caixa.repetido) {
      repetidosSeguidos += 1
      continue
    }
    repetidosSeguidos = 0
    total += 1
    const x = Math.max(0, Math.floor(caixa.x - MARGEM_FOCO))
    const y = Math.max(0, Math.floor(caixa.y - MARGEM_FOCO))
    const clip = {
      x,
      y,
      width: Math.max(1, Math.min(janela.width, Math.ceil(caixa.x + caixa.w + MARGEM_FOCO)) - x),
      height: Math.max(1, Math.min(janela.height, Math.ceil(caixa.y + caixa.h + MARGEM_FOCO)) - y),
    }
    await page.waitForTimeout(120) // o fim de qualquer transição do indicador
    const com = (await page.screenshot({ clip, animations: 'disabled' })).toString('base64')
    await page.evaluate(() => document.getElementById('__sorvedouro')?.focus())
    await page.waitForTimeout(120)
    const sem64 = (await page.screenshot({ clip, animations: 'disabled' })).toString('base64')
    const { pixels } = (await page.evaluate(`(${compararFotos})(${JSON.stringify({ com, sem: sem64 })})`)) as { pixels: number }
    // Um anel de 2 px ao redor do elemento tem, no mínimo, 2 x o perímetro em pixels; exige-se três quartos disso (cantos arredondados, recortes).
    const exigido = Math.round(0.75 * ESPESSURA_MINIMA * 2 * (Math.min(caixa.w, janela.width) + Math.min(caixa.h, janela.height)))
    if (pixels < exigido) sem.push(`${caixa.nome} (${pixels} px de indicador, mínimo ${exigido})`)
    // Devolve o foco ao elemento para o próximo Tab seguir a ordem do percurso (em campos de data e hora, recomeça no primeiro pedaço).
    await page.evaluate(() => (window as unknown as { __alvoFoco?: HTMLElement }).__alvoFoco?.focus())
  }
  await page.evaluate(() => {
    document.getElementById('__sorvedouro')?.remove()
    ;(document.activeElement as HTMLElement | null)?.blur()
  })
  return { sem, total }
}

/**
 * Alvos de toque: largura E altura de pelo menos 44 px, para todo controle (inclusive caixas de marcar e botões de opção, cuja área
 * clicável é a união do próprio controle com os rótulos que o acompanham).
 */
export async function alvosPequenos(page: Page): Promise<string[]> {
  return (await page.evaluate(`(() => {
    const descrever = ${descricao}
    const itens = [...document.querySelectorAll('button, [role=button], input:not([type=hidden]), select, textarea, nav a, a.button')]
    const medida = (el) => {
      const rects = [el.getBoundingClientRect()]
      if (el instanceof HTMLInputElement && (el.type === 'checkbox' || el.type === 'radio')) for (const l of el.labels ?? []) rects.push(l.getBoundingClientRect())
      const vis = rects.filter((r) => r.width > 0 && r.height > 0)
      if (vis.length === 0) return null
      const x0 = Math.min(...vis.map((r) => r.left)), x1 = Math.max(...vis.map((r) => r.right))
      const y0 = Math.min(...vis.map((r) => r.top)), y1 = Math.max(...vis.map((r) => r.bottom))
      return { w: x1 - x0, h: y1 - y0 }
    }
    return itens.filter((el) => { const m = medida(el); return m !== null && (m.h < 44 || m.w < 44) }).map(descrever)
  })()`)) as string[]
}
