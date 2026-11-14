/** Estado de carregamento: mantém o texto (leitores de tela e testes) e mostra três pontos que lembram a fila andando. */
export function Loading({ children = 'Carregando…' }: { children?: string }) {
  return (
    <p role="status" className="loading">
      <span className="loading-dots" aria-hidden="true">
        <i />
        <i />
        <i />
      </span>
      {children}
    </p>
  )
}
