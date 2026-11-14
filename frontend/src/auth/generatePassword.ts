const ALPHABET = 'abcdefghjkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789' // sem caracteres que se confundem (l, 1, I, O, 0)

/** Senha provisória aleatória (criptograficamente) de 14 caracteres; cumpre a política (10 caracteres, bem abaixo de 72 bytes). */
export function generatePassword(length = 14): string {
  const bytes = new Uint32Array(length)
  crypto.getRandomValues(bytes)
  return Array.from(bytes, (b) => ALPHABET[b % ALPHABET.length]).join('')
}
