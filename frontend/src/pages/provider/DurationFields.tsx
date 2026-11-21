import { MAX_COAT_EXTRA_MINUTES, MAX_SERVICE_MINUTES, MIN_SERVICE_MINUTES } from '../../api/duration'
import { Field } from '../Field'
import type { DurationErrors, DurationText } from './durationText'

/** Os três campos opcionais de duração, numa linha de três colunas (como os preços). `idPrefix` torna os ids únicos entre formulários. */
export function DurationFields({
  idPrefix,
  text,
  errors,
  disabled,
  onChange,
}: {
  idPrefix: string
  text: DurationText
  errors: DurationErrors
  disabled: boolean
  onChange: (next: DurationText, clear: keyof DurationErrors) => void
}) {
  const props = { type: 'number' as const, inputMode: 'numeric' as const, step: 5, disabled }
  return (
    <div className="field-row field-row--three">
      <Field
        {...props}
        label="Duração para porte médio (min, opcional)"
        name={`${idPrefix}-duration-medium`}
        min={MIN_SERVICE_MINUTES}
        max={MAX_SERVICE_MINUTES}
        hint="Vazio = usa a duração base."
        value={text.medium}
        error={errors.durationMedium}
        onChange={(e) => onChange({ ...text, medium: e.target.value }, 'durationMedium')}
      />
      <Field
        {...props}
        label="Duração para porte grande (min, opcional)"
        name={`${idPrefix}-duration-large`}
        min={MIN_SERVICE_MINUTES}
        max={MAX_SERVICE_MINUTES}
        hint="Vazio = usa a duração base."
        value={text.large}
        error={errors.durationLarge}
        onChange={(e) => onChange({ ...text, large: e.target.value }, 'durationLarge')}
      />
      <Field
        {...props}
        label="Extra por tipo de pelo (min, opcional)"
        name={`${idPrefix}-coat-extra`}
        min={0}
        max={MAX_COAT_EXTRA_MINUTES}
        hint="Somados ao porte quando o pelo é longo, cacheado ou duplo. Vazio = nenhum."
        value={text.coatExtra}
        error={errors.coatExtra}
        onChange={(e) => onChange({ ...text, coatExtra: e.target.value }, 'coatExtra')}
      />
    </div>
  )
}
