/** Tempo de espera desde a chegada, em texto: "espera de 12 min", "espera de 1 h 05". Nunca negativo: relógio do aparelho adiantado vira "menos de 1 min". */
export function waitLabel(checkedInAt: string, now: Date): string {
  const minutes = Math.floor((now.getTime() - Date.parse(checkedInAt)) / 60_000)
  if (!(minutes >= 1)) return 'espera de menos de 1 min'
  if (minutes < 60) return `espera de ${minutes} min`
  const rest = minutes % 60
  return `espera de ${Math.floor(minutes / 60)} h${rest > 0 ? ` ${String(rest).padStart(2, '0')}` : ''}`
}
