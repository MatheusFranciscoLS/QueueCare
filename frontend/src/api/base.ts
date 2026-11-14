/**
 * Endereço da API. Em desenvolvimento fica VAZIO (o Vite redireciona /api para a API local); na publicação o front e a API ficam em
 * endereços diferentes e o valor vem de VITE_API_URL na hora do build (ex.: https://queuecare-api.onrender.com). Valor inválido é
 * erro de configuração e falha alto, em vez de mandar o token para um lugar errado.
 */
export function apiUrl(path: string): string {
  const raw = String(import.meta.env.VITE_API_URL ?? '').trim()
  if (raw === '') return path
  let base: URL
  try {
    base = new URL(raw)
  } catch {
    throw new Error('VITE_API_URL inválida: informe um endereço completo, como https://api.exemplo.com')
  }
  if ((base.protocol !== 'https:' && base.protocol !== 'http:') || base.username || base.password || base.search || base.hash || (base.pathname !== '/' && base.pathname !== '')) {
    throw new Error('VITE_API_URL inválida: use só o endereço (https://api.exemplo.com), sem caminho nem parâmetros')
  }
  // O token Bearer não pode viajar sem criptografia: http só vale para a máquina local (desenvolvimento).
  const local = ['localhost', '127.0.0.1', '[::1]'].includes(base.hostname)
  if (base.protocol === 'http:' && !local) {
    throw new Error('VITE_API_URL inválida: use https (http só é aceito para localhost)')
  }
  return `${base.origin}${path}`
}
