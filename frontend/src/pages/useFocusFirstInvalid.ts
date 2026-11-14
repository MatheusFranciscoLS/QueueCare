import { useEffect, useState } from 'react'

/**
 * Depois de uma submissão COM erro, leva o foco ao primeiro campo inválido (leitores de tela leem o erro ligado a ele).
 * Devolve a função a chamar quando a submissão falha; digitar e limpar erros NÃO mexe no foco.
 */
export function useFocusFirstInvalid(): () => void {
  const [tick, setTick] = useState(0)
  useEffect(() => {
    if (tick > 0) document.querySelector<HTMLElement>('[aria-invalid="true"]')?.focus()
  }, [tick])
  return () => setTick((t) => t + 1)
}
