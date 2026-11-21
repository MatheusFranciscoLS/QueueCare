/**
 * Registra o service worker (ADR 0030) só no site publicado: em desenvolvimento ele atrapalha (guarda arquivos que mudam a cada salvamento) e nos testes não existe.
 * Falhar em registrar nunca quebra o app: ele só deixa de abrir offline.
 */
export function registerServiceWorker(): void {
  if (!import.meta.env.PROD || !('serviceWorker' in navigator)) return
  window.addEventListener('load', () => {
    navigator.serviceWorker.register('/sw.js', { scope: '/' }).catch(() => undefined)
  })
}
