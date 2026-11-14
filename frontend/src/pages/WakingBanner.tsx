import { useServerWaking } from '../api/slow'

/** Aviso amigável quando uma resposta demora: na hospedagem gratuita o servidor dorme sem uso e leva cerca de um minuto para acordar. */
export function WakingBanner() {
  const waking = useServerWaking()
  return (
    <p role="status" className="notice warning" hidden={!waking}>
      Isto está demorando mais que o normal. Na hospedagem gratuita o servidor dorme quando ninguém usa e leva cerca de um minuto para acordar. Aguarde: não precisa recarregar a página.
    </p>
  )
}
