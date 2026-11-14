import { act, renderHook, waitFor } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { useLoad } from '../pages/useLoad'

function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (error: unknown) => void
  const promise = new Promise<T>((res, rej) => {
    resolve = res
    reject = rej
  })
  return { promise, resolve, reject }
}

describe('useLoad', () => {
  it('carrega ao abrir e informa carregando até a resposta', async () => {
    const load = () => Promise.resolve(['a'])
    const { result } = renderHook(() => useLoad(load))
    expect(result.current.loading).toBe(true)
    await waitFor(() => expect(result.current.data).toEqual(['a']))
    expect(result.current.loading).toBe(false)
    expect(result.current.error).toBeUndefined()
  })

  it('uma resposta ANTIGA e lenta nunca sobrescreve a mais nova', async () => {
    const first = deferred<string>()
    const second = deferred<string>()
    const calls = [first, second]
    const load = vi.fn(() => calls.shift()!.promise)
    const { result } = renderHook(() => useLoad(load))

    act(() => { void result.current.reload() }) // segundo carregamento começa antes de o primeiro terminar
    await act(async () => second.resolve('novo'))
    await act(async () => first.resolve('antigo'))

    expect(result.current.data).toBe('novo')
  })

  it('resposta que chega depois de a tela fechar é descartada (nenhum aviso de atualização)', async () => {
    const late = deferred<string>()
    const spy = vi.spyOn(console, 'error').mockImplementation(() => {})
    const load = () => late.promise
    const { result, unmount } = renderHook(() => useLoad(load))
    unmount()

    await act(async () => late.resolve('tarde'))

    expect(result.current.data).toBeUndefined()
    expect(spy).not.toHaveBeenCalled()
    spy.mockRestore()
  })

  it('recarregar depois de gravar descarta a resposta do carregamento anterior ainda pendente', async () => {
    const beforeSave = deferred<string[]>()
    const afterSave = deferred<string[]>()
    const calls = [beforeSave, afterSave]
    const load = () => calls.shift()!.promise
    const { result } = renderHook(() => useLoad(load))

    act(() => { void result.current.reload() })
    await act(async () => afterSave.resolve(['com o novo item']))
    await act(async () => beforeSave.resolve(['sem o novo item']))

    expect(result.current.data).toEqual(['com o novo item'])
  })

  it('falha ao ATUALIZAR mantém o dado anterior e expõe o erro; o sucesso seguinte limpa o erro', async () => {
    let attempt = 0
    const load = vi.fn(async () => {
      attempt += 1
      if (attempt === 2) throw new Error('fora do ar')
      return `dado ${attempt}`
    })
    const { result } = renderHook(() => useLoad(load))
    await waitFor(() => expect(result.current.data).toBe('dado 1'))

    act(() => { void result.current.reload() })
    await waitFor(() => expect(result.current.error).toBeInstanceOf(Error))
    expect(result.current.data).toBe('dado 1') // não apaga a tela

    act(() => { void result.current.reload() })
    await waitFor(() => expect(result.current.data).toBe('dado 3'))
    expect(result.current.error).toBeUndefined()
  })

  it('a versão sobe a cada carregamento bem-sucedido, mesmo com conteúdo igual', async () => {
    const load = () => Promise.resolve({ same: true })
    const { result } = renderHook(() => useLoad(load))
    await waitFor(() => expect(result.current.version).toBe(1))

    act(() => { void result.current.reload() })
    await waitFor(() => expect(result.current.version).toBe(2))
  })

  it('uma falha não sobe a versão', async () => {
    let ok = true
    const load = async () => {
      if (!ok) throw new Error('x')
      return 1
    }
    const { result } = renderHook(() => useLoad(load))
    await waitFor(() => expect(result.current.version).toBe(1))
    ok = false
    act(() => { void result.current.reload() })
    await waitFor(() => expect(result.current.error).toBeDefined())
    expect(result.current.version).toBe(1)
  })
})

describe('useLoad: a promessa de reload', () => {
  it('termina quando o carregamento termina, com sucesso ou falha, e nunca rejeita', async () => {
    const pending = deferred<string>()
    let useSlow = false
    const load = () => (useSlow ? pending.promise : Promise.resolve('inicial'))
    const { result } = renderHook(() => useLoad(load))
    await waitFor(() => expect(result.current.data).toBe('inicial'))
    useSlow = true

    let finished = false
    act(() => {
      void result.current.reload().then(() => (finished = true))
    })
    await act(async () => {})
    expect(finished).toBe(false) // ainda esperando a resposta

    await act(async () => pending.reject(new Error('falhou')))
    await waitFor(() => expect(finished).toBe(true)) // terminou (e não rejeitou), mesmo com falha
    expect(result.current.error).toBeInstanceOf(Error)
  })
})

describe('useLoad: substituição e exceção síncrona', () => {
  it('a promessa de um carregamento SUBSTITUÍDO termina na hora, sem esperar a resposta antiga', async () => {
    const first = deferred<string>()
    const second = deferred<string>()
    const calls = [first, second]
    const load = () => calls.shift()!.promise
    const { result } = renderHook(() => useLoad(load))
    await act(async () => {}) // abertura: o primeiro GET está pendente

    let firstDone = false
    let secondDone = false
    act(() => {
      void result.current.reload().then(() => (secondDone = true)) // esta é a 2ª chamada; a da abertura (1ª) fica substituída
    })
    await act(async () => {})
    expect(secondDone).toBe(false) // a vigente continua esperando a SUA resposta

    // a promessa de um reload feito antes do último é a que termina por substituição
    const third = deferred<string>()
    calls.push(third)
    act(() => {
      void result.current.reload().then(() => (firstDone = true))
    })
    await act(async () => {})

    expect(secondDone).toBe(true) // a anterior terminou assim que a nova começou, sem resposta nenhuma
    expect(firstDone).toBe(false)
    await act(async () => third.resolve('último'))
    await waitFor(() => expect(result.current.data).toBe('último'))
    expect(firstDone).toBe(true)
  })

  it('exceção SÍNCRONA em load vira erro de carregamento: o reload não rejeita', async () => {
    let boom = false
    const load = (): Promise<string> => {
      if (boom) throw new Error('lançou direto')
      return Promise.resolve('ok')
    }
    const { result } = renderHook(() => useLoad(load))
    await waitFor(() => expect(result.current.data).toBe('ok'))
    boom = true

    await act(async () => {
      await expect(result.current.reload()).resolves.toBeUndefined()
    })

    expect(result.current.error).toBeInstanceOf(Error)
    expect(result.current.data).toBe('ok') // o dado anterior permanece
  })

  it('fechar a tela encerra a promessa pendente', async () => {
    const never = deferred<string>()
    const load = () => never.promise
    const { result, unmount } = renderHook(() => useLoad(load))
    let done = false
    act(() => {
      void result.current.reload().then(() => (done = true))
    })

    unmount()
    await act(async () => {})

    expect(done).toBe(true)
  })
})
