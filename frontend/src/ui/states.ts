/** Estados do atendimento e seus rótulos em texto (o estado nunca é só cor ou forma). */
export type AppointmentState = 'scheduled' | 'waiting' | 'done' | 'cancelled'

export const STATE_LABEL: Record<AppointmentState, string> = {
  scheduled: 'Agendado',
  waiting: 'Chegou',
  done: 'Concluído',
  cancelled: 'Cancelado',
}
