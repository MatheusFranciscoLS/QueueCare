/** Mesma política da API (ADR 0003): mínimo de 10 caracteres e no máximo 72 bytes em UTF-8 (limite do BCrypt). A API confere de novo. */
export const MIN_PASSWORD_CHARACTERS = 10
export const MAX_PASSWORD_BYTES = 72

export function validateNewPassword(password: string, current?: string): string | null {
  if ([...password].length < MIN_PASSWORD_CHARACTERS) {
    return `A senha deve ter pelo menos ${MIN_PASSWORD_CHARACTERS} caracteres.`
  }
  if (new TextEncoder().encode(password).length > MAX_PASSWORD_BYTES) {
    return `A senha deve ter no máximo ${MAX_PASSWORD_BYTES} bytes (acentos e símbolos contam mais de um).`
  }
  if (current !== undefined && password === current) {
    return 'A nova senha deve ser diferente da atual.'
  }
  return null
}
