import { useEffect, useRef, useState, type ReactNode } from 'react'

/**
 * Senha provisória recém-definida, mostrada UMA vez para o administrador entregar por um canal seguro. Só em memória desta tela:
 * nunca em localStorage, sessionStorage nem log, e some ao limpar, ao sair da tela ou ao sair da conta. Ao aparecer, recebe o foco.
 */
export function PasswordDelivery({ children, password, onDone }: { children: ReactNode; password: string; onDone: () => void }) {
  const [copyMessage, setCopyMessage] = useState<string | null>(null)
  const ref = useRef<HTMLDivElement>(null)

  useEffect(() => {
    ref.current?.focus()
  }, [])

  async function copy() {
    try {
      await navigator.clipboard.writeText(password)
      setCopyMessage('Senha copiada.')
    } catch {
      setCopyMessage('Não foi possível copiar automaticamente: selecione a senha acima e copie.')
    }
  }

  return (
    <div role="status" className="notice" ref={ref} tabIndex={-1}>
      <p>{children}</p>
      <label htmlFor="delivered-password">Senha provisória</label>
      <input id="delivered-password" readOnly value={password} onFocus={(e) => e.currentTarget.select()} />
      <p className="actions">
        <button type="button" className="button secondary" onClick={copy}>
          Copiar senha
        </button>
        <button type="button" className="button" onClick={onDone}>
          Já entreguei, limpar da tela
        </button>
      </p>
      <p role="status" className="hint" hidden={!copyMessage}>
        {copyMessage}
      </p>
    </div>
  )
}
