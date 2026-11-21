import { useEffect, useState } from 'react'

/** Está com internet? Segue os eventos do navegador (online/offline); começa pelo estado atual. */
export function useOnline(): boolean {
  const [online, setOnline] = useState(() => (typeof navigator === 'undefined' ? true : navigator.onLine))
  useEffect(() => {
    const on = () => setOnline(true)
    const off = () => setOnline(false)
    window.addEventListener('online', on)
    window.addEventListener('offline', off)
    return () => {
      window.removeEventListener('online', on)
      window.removeEventListener('offline', off)
    }
  }, [])
  return online
}

/**
 * Aviso de falta de internet (ADR 0030). O app abre offline, mas as ações dependem da API: o aviso diz isso ANTES de a pessoa tentar e achar que salvou.
 * Fica fora do fluxo (`role="status"` educado, sem roubar o foco) e some sozinho quando a conexão volta.
 */
export function OfflineBanner() {
  const online = useOnline()
  if (online) return null
  return (
    <p role="status" className="offline-banner">
      <strong>Sem internet.</strong> O que você vê pode estar desatualizado e nenhuma ação será enviada até a conexão voltar.
    </p>
  )
}
