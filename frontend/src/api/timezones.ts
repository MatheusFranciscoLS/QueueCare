export const DEFAULT_TIME_ZONE = 'America/Sao_Paulo'

/** Fusos IANA oferecidos no formulário (o navegador sabe a lista); o do Brasil vem primeiro. A API confere o valor de novo. */
export function timeZones(current?: string): string[] {
  let all: string[] = []
  try {
    all = Intl.supportedValuesOf('timeZone')
  } catch {
    all = []
  }
  const rest = all.filter((z) => z !== DEFAULT_TIME_ZONE)
  const list = [DEFAULT_TIME_ZONE, ...rest]
  if (current && !list.includes(current)) list.push(current) // um fuso já gravado nunca some da lista
  return list
}
