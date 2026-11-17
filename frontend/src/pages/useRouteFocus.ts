import { useEffect, useRef, type RefObject } from 'react'
import { useLocation } from 'react-router-dom'

export const SITE_NAME = 'Pelo & Ponto'

/**
 * Título da página por rota e foco no título quando a rota muda (ADR 0018, seção 4).
 * O título (h1) costuma aparecer só depois do carregamento da tela: observa o conteúdo até ele existir (no máximo 5 s).
 * Na primeira carga da página o foco não é movido (o navegador já começa no topo); nas trocas de rota, sim.
 */
export function useRouteFocus(main: RefObject<HTMLElement | null>): void {
  const { pathname } = useLocation()
  // Endereço da última execução: uma troca REAL de rota muda o endereço; o ciclo extra do StrictMode repete o mesmo e não conta.
  const lastPath = useRef<string | null>(null)

  useEffect(() => {
    const area = main.current
    if (!area) return
    const moveFocus = lastPath.current !== null && lastPath.current !== pathname
    lastPath.current = pathname

    const apply = (): boolean => {
      const heading = area.querySelector('h1')
      if (!heading) return false
      document.title = `${heading.textContent} · ${SITE_NAME}`
      if (moveFocus) {
        heading.setAttribute('tabindex', '-1')
        heading.focus()
      }
      return true
    }

    if (apply()) return
    const observer = new MutationObserver(() => {
      if (apply()) observer.disconnect()
    })
    observer.observe(area, { childList: true, subtree: true })
    const stop = window.setTimeout(() => observer.disconnect(), 5000)
    return () => {
      observer.disconnect()
      window.clearTimeout(stop)
    }
  }, [main, pathname])
}
