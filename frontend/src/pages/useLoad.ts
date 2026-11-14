import { useCallback, useEffect, useRef, useState } from 'react'

export interface Loaded<T> {
  data: T | undefined
  error: unknown
  loading: boolean
  /**
   * Recarrega. A promessa termina quando ESTE carregamento termina (com sucesso ou com falha) OU assim que ele é substituído por
   * outro mais novo (ou a tela fecha), sem esperar a resposta antiga. Nunca rejeita, nem se `load` lançar de forma síncrona.
   */
  reload: () => Promise<void>
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
  /** Encerra a promessa do carregamento vigente (usado quando ele é substituído ou a tela fecha). */
  const settleCurrent = useRef<(() => void) | undefined>(undefined)

  const start = useCallback(
    (showLoading: boolean): Promise<void> => {
      settleCurrent.current?.() // o carregamento anterior, se ainda pendente, deixa de ser esperado
      const mine = ++run.current
      if (showLoading) setLoading(true)
      return new Promise<void>((resolve) => {
        settleCurrent.current = resolve
        // Promise.resolve().then(load): uma exceção síncrona de `load` também vira falha de carregamento, nunca rejeição.
        Promise.resolve()
          .then(load)
          .then(
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
          .then(resolve)
      })
    },
    [load],
  )

  useEffect(() => {
    // oxlint-disable-next-line react/set-state-in-effect -- buscar dados ao abrir a tela é o uso legítimo do efeito; o setState só ocorre quando a resposta chega
    void start(false) // na abertura o estado inicial já é "carregando"
    return () => {
      run.current += 1 // invalida o que ainda estiver a caminho
      settleCurrent.current?.()
    }
  }, [start])

  const reload = useCallback(() => start(true), [start])
  return { data, error, loading, reload, version }
}
