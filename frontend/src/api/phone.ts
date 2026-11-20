/** Telefone no formato da API: só dígitos com DDI do Brasil (55 + DDD + número). */

/** "5511912345678" vira "(11) 91234-5678"; o que não tem esse formato volta como veio. */
export function formatPhone(digits: string | null | undefined): string {
  if (!digits) return ''
  const m = /^55(\d{2})(\d{4,5})(\d{4})$/.exec(digits)
  return m ? `(${m[1]}) ${m[2]}-${m[3]}` : digits
}

/** Conferência antes de enviar: aceita máscara e DDI; a API repete a regra (e o banco também). Devolve a mensagem de erro, ou nada. */
export function validatePhone(typed: string): string | undefined {
  const text = typed.trim()
  if (!text) return 'Informe o seu WhatsApp, com DDD.'
  if (!/^[0-9 ()+.-]+$/.test(text)) return 'Use só números, com DDD. Exemplo: (11) 91234-5678.'
  let digits = text.replace(/\D/g, '')
  if (!((digits.length === 12 || digits.length === 13) && digits.startsWith('55'))) {
    if (text.startsWith('+') || digits.length < 10 || digits.length > 11) return 'Informe o DDD e o número. Exemplo: (11) 91234-5678.'
    digits = `55${digits}`
  }
  const area = digits.slice(2, 4)
  const number = digits.slice(4)
  const areaOk = area[0] !== '0' && area[1] !== '0'
  const numberOk = number.length === 9 ? number[0] === '9' : number[0] >= '2' && number[0] <= '5'
  return areaOk && numberOk ? undefined : 'Esse telefone não parece válido. Exemplo: (11) 91234-5678.'
}

/** Link do WhatsApp (sem integração paga: abre a conversa com a mensagem pronta). */
export function whatsappLink(digits: string, message?: string): string {
  return `https://wa.me/${digits}${message ? `?text=${encodeURIComponent(message)}` : ''}`
}
