/** Estados do atendimento e seus rótulos em texto (o estado nunca é só cor ou forma). */
export type AppointmentState = 'scheduled' | 'waiting' | 'inprogress' | 'ready' | 'done' | 'cancelled'

export const STATE_LABEL: Record<AppointmentState, string> = {
  scheduled: 'Agendado',
  waiting: 'Chegou',
  inprogress: 'Em atendimento',
  ready: 'Pronto',
  done: 'Concluído',
  cancelled: 'Cancelado',
}
