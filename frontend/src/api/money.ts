/** Dinheiro em CENTAVOS inteiros (ADR 0019); nunca ponto flutuante. O teto é o da API: R$ 100.000,00. */
export const MAX_PRICE_CENTS = 10_000_000

/** 5000 vira "R$ 50,00"; 123456 vira "R$ 1.234,56". */
export function formatMoney(cents: number): string {
  return new Intl.NumberFormat('pt-BR', { style: 'currency', currency: 'BRL' }).format(cents / 100).replace(/ /g, ' ')
}

/**
 * Valor digitado em reais para centavos: "50", "50,5", "50,50", "1.234,50", "R$ 50". Vazio é "a combinar" (nulo). Devolve `undefined` se não
 * for dinheiro (letras, mais de duas casas, negativo ou acima do teto), para a tela mostrar o erro.
 */
export function parseMoney(typed: string): number | null | undefined {
  const text = typed.trim().replace(/^R\$\s*/i, '')
  if (!text) return null
  if (!/^\d{1,3}(\.\d{3})*(,\d{1,2})?$|^\d+(,\d{1,2})?$|^\d+\.\d{1,2}$/.test(text)) return undefined
  const normalized = /,/.test(text) ? text.replace(/\./g, '').replace(',', '.') : text.includes('.') && /\.\d{3}/.test(text) ? text.replace(/\./g, '') : text
  const cents = Math.round(Number(normalized) * 100)
  return Number.isFinite(cents) && cents >= 0 && cents <= MAX_PRICE_CENTS ? cents : undefined
}

/** Centavos de volta para o campo ("5000" → "50,00"). */
export function moneyInput(cents: number | null | undefined): string {
  return cents == null ? '' : (cents / 100).toFixed(2).replace('.', ',')
}

export interface Prices {
  priceCents?: number | null
  priceMediumCents?: number | null
  priceLargeCents?: number | null
}

/** O preço para o porte do pet: médio e grande usam o próprio valor se houver, senão o base; pequeno e sem porte usam o base (mesma regra da API). */
export function priceForSize(prices: Prices, size: string | null | undefined): number | null {
  if (size === 'MEDIUM' && prices.priceMediumCents != null) return prices.priceMediumCents
  if (size === 'LARGE' && prices.priceLargeCents != null) return prices.priceLargeCents
  return prices.priceCents ?? null
}

/** Texto do preço de um serviço sem saber o pet: "R$ 50,00", "a partir de R$ 50,00" (varia por porte) ou "a combinar". */
export function priceSummary(prices: Prices): string {
  const all = [prices.priceCents, prices.priceMediumCents, prices.priceLargeCents].filter((p): p is number => p != null)
  if (all.length === 0) return 'a combinar'
  const base = prices.priceCents ?? null
  const differs = prices.priceMediumCents != null || prices.priceLargeCents != null
  if (!differs) return formatMoney(base ?? 0)
  return `a partir de ${formatMoney(Math.min(...all))}`
}
