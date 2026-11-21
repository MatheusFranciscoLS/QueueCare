/** Altura-base de uma hora na grade da semana, em px. */
export const BASE_HOUR_PX = 68
/** Menor altura de um bloco: é um botão, e alvo de toque tem pelo menos 44 px (o portão estrito de acessibilidade mede isso); cabe horário e nome em uma linha. */
export const MIN_BLOCK_PX = 44
/** Folga entre blocos vizinhos (dois atendimentos seguidos nunca se encostam nem se sobrepõem). */
export const GAP_PX = 3
/** Limite da altura da hora: uma semana com atendimentos de 15 minutos fica mais alta, mas não infinita. */
export const MAX_HOUR_PX = 188

/**
 * Altura da hora da grade para a semana: a base (68 px), ou maior se houver atendimento curto, para que o MENOR bloco tenha pelo menos
 * MIN_BLOCK_PX de altura. A escala continua PROPORCIONAL (a duração é percebida pelo tamanho); só o zoom muda.
 */
export function hourHeight(durations: number[]): number {
  const shortest = Math.min(...durations.filter((d) => d > 0))
  if (!Number.isFinite(shortest)) return BASE_HOUR_PX
  const needed = Math.ceil(((MIN_BLOCK_PX + GAP_PX) * 60) / shortest)
  return Math.min(MAX_HOUR_PX, Math.max(BASE_HOUR_PX, needed))
}

/** Posição e altura de um bloco na coluna do dia, em px, a partir do início (minutos desde o primeiro horário do eixo) e da duração. */
export function blockBox(startMinutes: number, duration: number, hourPx: number): { top: number; height: number } {
  return { top: (startMinutes * hourPx) / 60, height: (duration * hourPx) / 60 - GAP_PX }
}
