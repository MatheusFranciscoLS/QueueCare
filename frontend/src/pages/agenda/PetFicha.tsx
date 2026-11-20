import { fichaSummary, TEMPERAMENT_LABEL, type PetOnAppointment } from '../../api/pets'

/**
 * A ficha do pet para quem atende (ADR 0019): primeiro o aviso, se há (comportamento "reativo" vem em destaque e com texto, nunca só cor);
 * depois a linha com pelo, peso e vacinas e as observações de saúde. Só mostra o que existe; sem ficha não desenha nada.
 */
export function PetFicha({ pet }: { pet: PetOnAppointment }) {
  const summary = fichaSummary(pet)
  const reactive = pet.temperament === 'REACTIVE'
  const calmLabel = pet.temperament && !reactive ? TEMPERAMENT_LABEL[pet.temperament] : null
  if (!summary && !reactive && !calmLabel && !pet.healthNotes) return null
  return (
    <>
      {reactive && <strong className="appt-alert">Atenção: pode reagir ou morder</strong>}
      {(summary || calmLabel) && <span className="appt-ficha">{[summary, calmLabel].filter(Boolean).join(' · ')}</span>}
      {pet.healthNotes && <span className="appt-notes">Saúde e alergias: {pet.healthNotes}</span>}
    </>
  )
}
