import { useId, type ReactNode } from 'react'

/**
 * "Mais ações" (revisão do ChatGPT, 21/11/2026): cada atendimento mostra UMA ação principal para o momento e deixa as demais atrás deste botão, em vez de
 * seis botões do mesmo peso. As ações ficam NA LINHA, ao lado (não num menu flutuante), então teclado, leitor de tela e o foco de quem abriu funcionam como
 * em qualquer botão. Controlado pelo pai: a confirmação de uma ação mantém o grupo aberto, para o foco voltar ao botão que a abriu.
 */
export function MoreActions({
  who,
  open,
  onOpenChange,
  disabled,
  children,
}: {
  /** Quem é o atendimento (nome do tutor ou do pet): vai no nome acessível. */
  who: string
  open: boolean
  onOpenChange: (open: boolean) => void
  disabled?: boolean
  children: ReactNode
}) {
  const id = useId()
  return (
    <>
      <button
        type="button"
        className="button secondary more-toggle"
        aria-expanded={open}
        aria-controls={open ? id : undefined}
        aria-label={`Mais ações de ${who}`}
        disabled={disabled}
        onClick={() => onOpenChange(!open)}
      >
        Mais ações
      </button>
      {open && (
        <span id={id} role="group" aria-label={`Mais ações de ${who}`} className="more-actions">
          {children}
        </span>
      )}
    </>
  )
}
