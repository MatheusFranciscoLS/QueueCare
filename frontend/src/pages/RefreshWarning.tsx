/** Falha ao ATUALIZAR uma tela que já tinha dados: os dados antigos continuam visíveis, mas a pessoa é avisada de que podem estar desatualizados. */
export function RefreshWarning({ onRetry }: { onRetry: () => void }) {
  return (
    <div role="alert" className="notice warning">
      <p>Não foi possível atualizar os dados. O que aparece abaixo pode estar desatualizado.</p>
      <button type="button" className="button secondary" onClick={onRetry}>
        Tentar de novo
      </button>
    </div>
  )
}
