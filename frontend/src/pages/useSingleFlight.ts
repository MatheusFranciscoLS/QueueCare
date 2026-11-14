import { useRef } from 'react'

/** Garante UMA submissão por vez: um segundo envio (Enter repetido, duplo clique) enquanto o primeiro não terminou é ignorado. */
export function useSingleFlight(): (action: () => Promise<void>) => Promise<void> {
  const running = useRef(false)
  return async (action) => {
    if (running.current) return
    running.current = true
    try {
      await action()
    } finally {
      running.current = false
    }
  }
}
