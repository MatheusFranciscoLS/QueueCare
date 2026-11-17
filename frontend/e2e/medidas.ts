import type { Page } from '@playwright/test'

// Medidas reutilizáveis da verificação em navegador real (ADR 0018, seção 8): foco visível (pixels) e alvos de toque.
// Têm teste próprio em verificador.spec.ts: uma medida que não reprova o que deve reprovar não serve de critério.

const descricao = `(el) => {
  const tag = el.tagName.toLowerCase()
  const nome = (el.getAttribute('aria-label') || el.textContent || el.getAttribute('name') || '').trim().slice(0, 40)
  return tag + (nome ? ' "' + nome + '"' : '')
}`

/**
 * Foco VISÍVEL, medido pelos PIXELS e pela GEOMETRIA (uma sombra transparente ou um contorno sem contraste passariam numa checagem de CSS,
 * e uma mudança de fundo passaria numa contagem solta de pixels). Para cada elemento que recebe foco por Tab: fotografa a região dele
 * (com uma margem) COM foco e SEM foco e marca o pixel que mudou com contraste de pelo menos 3:1 contra o anterior (WCAG 2.4.13).
 * Em cada um dos quatro lados, o indicador precisa ocupar uma faixa de pelo menos 2 px de espessura em 95 % do trecho reto do lado, de pixels SEGUIDOS (dois pixels soltos não formam anel)
 * (a faixa vai da margem de fora até 3 px para dentro da borda, o que aceita anel por fora, no limite e por dentro; os cantos, de
 * onde sai a curvatura, ficam de fora do trecho reto). E o interior do elemento não pode ter mudado em sua maior parte: fundo que
 * muda inteiro não é anel. O elemento é centralizado na janela antes de medir; se mesmo assim algum lado não couber (colado na borda ou maior que a janela), REPROVA: nenhum lado fica de fora.
 */
const MARGEM_FOCO = 6
const CONTRASTE_MINIMO = 3
const ESPESSURA_MINIMA = 2
const FAIXA_PARA_DENTRO = 3
const COBERTURA_MINIMA = 0.95
const INTERIOR_MAXIMO = 0.5

/** Roda dentro da página: recebe as duas fotos (base64 PNG) e o retângulo do elemento; devolve os lados sem anel e a mudança do interior. */
const compararFotos = `async (a) => {
  const carregar = async (b64) => {
    const bytes = Uint8Array.from(atob(b64), (c) => c.charCodeAt(0))
    const bmp = await createImageBitmap(new Blob([bytes], { type: 'image/png' }))
    const cv = new OffscreenCanvas(bmp.width, bmp.height)
    const cx = cv.getContext('2d')
    cx.drawImage(bmp, 0, 0)
    return cx.getImageData(0, 0, bmp.width, bmp.height)
  }
  const [com, sem] = [await carregar(a.com), await carregar(a.sem)]
  const W = com.width, H = com.height
  const lin = (v) => { const s = v / 255; return s <= 0.03928 ? s / 12.92 : Math.pow((s + 0.055) / 1.055, 2.4) }
  const lum = (d, i) => 0.2126 * lin(d[i]) + 0.7152 * lin(d[i + 1]) + 0.0722 * lin(d[i + 2])
  const mudou = new Uint8Array(W * H)
  for (let p = 0; p < W * H; p++) {
    const l1 = lum(com.data, p * 4), l2 = lum(sem.data, p * 4)
    mudou[p] = (Math.max(l1, l2) + 0.05) / (Math.min(l1, l2) + 0.05) >= ${CONTRASTE_MINIMO} ? 1 : 0
  }
  const em = (x, y) => (x >= 0 && y >= 0 && x < W && y < H ? mudou[y * W + x] : 0)
  const rx = Math.round(a.rx), ry = Math.round(a.ry), rw = Math.round(a.rw), rh = Math.round(a.rh)
  const m = ${MARGEM_FOCO}, dentro = ${FAIXA_PARA_DENTRO}
  const reto = (comprimento) => Math.min(12, Math.floor(comprimento / 4)) // fora dos cantos
  const cobertura = (lado) => {
    const horizontal = lado === 'top' || lado === 'bottom'
    const compr = horizontal ? rw : rh
    const e = reto(compr)
    let boas = 0, total = 0
    for (let t = e; t < compr - e; t++) {
      let seguidos = 0, maior = 0 // espessura = pixels que mudaram SEGUIDOS, na direção perpendicular à borda
      for (let k = -m; k < dentro; k++) {
        const x = horizontal ? rx + t : lado === 'left' ? rx + k : rx + rw - 1 - k
        const y = horizontal ? (lado === 'top' ? ry + k : ry + rh - 1 - k) : ry + t
        seguidos = em(x, y) ? seguidos + 1 : 0
        if (seguidos > maior) maior = seguidos
      }
      total += 1
      if (maior >= ${ESPESSURA_MINIMA}) boas += 1
    }
    return total === 0 ? 0 : boas / total
  }
  const falhos = []
  for (const lado of ['top', 'bottom', 'left', 'right']) {
    if (!a.medido[lado]) continue
    if (cobertura(lado) < ${COBERTURA_MINIMA}) falhos.push(lado)
  }
  let mudouInterior = 0, totalInterior = 0
  for (let y = ry + dentro + 1; y < ry + rh - dentro - 1; y++) for (let x = rx + dentro + 1; x < rx + rw - dentro - 1; x++) { totalInterior += 1; mudouInterior += em(x, y) }
  return { falhos, interior: totalInterior === 0 ? 0 : mudouInterior / totalInterior, medidos: Object.values(a.medido).filter(Boolean).length }
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
      el.scrollIntoView({ block: 'center', inline: 'center' }) // centraliza para a margem de medida caber nos quatro lados
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
    // Elemento que SÓ APARECE ao receber o foco (atalho para o conteúdo): fora da janela sem foco e dentro dela com foco. O aparecimento é o indicador.
    const apareceAoFoco = await page.evaluate(`(() => { const r = window.__alvoFoco.getBoundingClientRect(); return r.bottom <= 0 || r.right <= 0 || r.top >= innerHeight || r.left >= innerWidth })()`)
    if (apareceAoFoco) {
      await page.evaluate(() => (window as unknown as { __alvoFoco?: HTMLElement }).__alvoFoco?.focus())
      continue
    }
    const sem64 = (await page.screenshot({ clip, animations: 'disabled' })).toString('base64')
    const medido = {
      top: caixa.y - MARGEM_FOCO >= 0,
      bottom: caixa.y + caixa.h + MARGEM_FOCO <= janela.height,
      left: caixa.x - MARGEM_FOCO >= 0,
      right: caixa.x + caixa.w + MARGEM_FOCO <= janela.width,
    }
    const r = (await page.evaluate(`(${compararFotos})(${JSON.stringify({ com, sem: sem64, rx: caixa.x - x, ry: caixa.y - y, rw: caixa.w, rh: caixa.h, medido })})`)) as {
      falhos: string[]
      interior: number
      medidos: number
    }
    const motivos: string[] = []
    if (r.medidos < 4) motivos.push(`só ${r.medidos} de 4 lados cabem na janela para medir (elemento colado na borda ou maior que a janela)`)
    if (r.falhos.length > 0) motivos.push(`sem anel de ${ESPESSURA_MINIMA} px em: ${r.falhos.join(', ')}`)
    if (r.interior > INTERIOR_MAXIMO) motivos.push(`o fundo inteiro muda (${Math.round(r.interior * 100)} %), isso não é um anel`)
    if (motivos.length > 0) sem.push(`${caixa.nome} (${motivos.join('; ')})`)
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
    const itens = [...document.querySelectorAll('button, [role=button], input:not([type=hidden]), select, textarea, a[href], a.button')]
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
