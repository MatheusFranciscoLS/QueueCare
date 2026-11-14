/** Rotas internas para onde o login pode devolver a pessoa. Qualquer outro valor (URL absoluta, //host, rota desconhecida) vira "/". */
const ALLOWED = new Set(['/', '/trocar-senha'])

export function safeDestination(candidate: unknown): string {
  return typeof candidate === 'string' && ALLOWED.has(candidate) ? candidate : '/'
}
