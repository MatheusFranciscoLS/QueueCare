import { useCallback, useEffect, useRef, useState } from 'react'

export interface Loaded<T> {
  data: T | undefined
  error: unknown
  loading: boolean
  reload: () => void
  /** Sobe a cada carregamento BEM-SUCEDIDO (mesmo com conteúdo igual): serve de chave para refazer editores. */
  version: number
}

/**
 * Carrega dados ao abrir a tela. Uma resposta que chega depois de a tela fechar, ou depois de um novo carregamento, é descartada
 * (nunca sobrescreve o dado mais novo). `load` precisa ser estável (useCallback) no componente que chama.
 */
export function useLoad<T>(load: () => Promise<T>): Loaded<T> {
  const [data, setData] = useState<T>()
  const [error, setError] = useState<unknown>()
  const [loading, setLoading] = useState(true)
  const [version, setVersion] = useState(0)
  const run = useRef(0)

  const start = useCallback(
    (showLoading: boolean) => {
      const mine = ++run.current
      if (showLoading) setLoading(true)
      load().then(
        (value) => {
          if (run.current !== mine) return
          setData(value)
          setError(undefined)
          setVersion((v) => v + 1)
          setLoading(false)
        },
        (e) => {
          if (run.current !== mine) return
          setError(e)
          setLoading(false)
        },
      )
    },
    [load],
  )

  useEffect(() => {
    // oxlint-disable-next-line react/set-state-in-effect -- buscar dados ao abrir a tela é o uso legítimo do efeito; o setState só ocorre quando a resposta chega
    start(false) // na abertura o estado inicial já é "carregando"
    return () => {
      run.current += 1 // invalida o que ainda estiver a caminho
    }
  }, [start])

  const reload = useCallback(() => start(true), [start])
  return { data, error, loading, reload, version }
}
