import { useSyncExternalStore } from 'react'

/** Depois deste tempo sem resposta a tela avisa que o servidor pode estar acordando (hospedagem gratuita dorme sem uso). */
export const SLOW_AFTER_MS = 4000

let slow = 0
const listeners = new Set<() => void>()

function emit() {
  listeners.forEach((listener) => listener())
}

/** Marca o início de uma requisição; devolve a função que marca o fim. Cada requisição pendente por mais de SLOW_AFTER_MS conta como "lenta". */
export function trackRequest(): () => void {
  let counted = false
  let finished = false
  const timer = setTimeout(() => {
    if (finished) return
    counted = true
    slow += 1
    emit()
  }, SLOW_AFTER_MS)
  return () => {
    if (finished) return
    finished = true
    clearTimeout(timer)
    if (counted) {
      slow -= 1
      emit()
    }
  }
}

/** Há alguma requisição demorando mais que o normal? */
export function useServerWaking(): boolean {
  return useSyncExternalStore(
    (listener) => {
      listeners.add(listener)
      return () => listeners.delete(listener)
    },
    () => slow > 0,
  )
}
