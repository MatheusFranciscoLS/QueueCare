import { useState, type FormEvent } from 'react'
import { ApiError } from '../../api/client'
import { describeError } from '../../api/messages'
import { COAT_LABEL, PET_LIMITS, SIZE_LABEL, SPECIES_LABEL, TEMPERAMENT_LABEL, parseWeight, type Pet, type PetInput } from '../../api/pets'
import { Field } from '../Field'
import { useSingleFlight } from '../useSingleFlight'

/** Lista suspensa de um campo opcional: "Não informado" mais as opções (valor da API → texto). */
function SelectField({
  label,
  name,
  value,
  error,
  disabled,
  empty,
  options,
  onChange,
}: {
  label: string
  name: string
  value: string
  error?: string
  disabled: boolean
  empty: string
  options: Record<string, string>
  onChange: (value: string) => void
}) {
  return (
    <div className="field">
      <label htmlFor={name}>{label}</label>
      <select id={name} name={name} disabled={disabled} value={value} aria-invalid={error ? true : undefined} aria-describedby={error ? `${name}-error` : undefined} onChange={(e) => onChange(e.target.value)}>
        <option value="">{empty}</option>
        {Object.entries(options).map(([v, text]) => (
          <option key={v} value={v}>
            {text}
          </option>
        ))}
      </select>
      {error && (
        <p id={`${name}-error`} className="error">
          {error}
        </p>
      )}
    </div>
  )
}

type Errors = Partial<Record<keyof PetInput | 'form', string>>

const EMPTY: PetInput = { name: '', species: '', breed: '', size: '', notes: '', coat: '', weightKg: '', temperament: '', healthNotes: '', vaccinated: '' }

function fromPet(pet: Pet): PetInput {
  return {
    name: pet.name,
    species: pet.species,
    breed: pet.breed ?? '',
    size: pet.size ?? '',
    notes: pet.notes ?? '',
    coat: pet.coat ?? '',
    weightKg: pet.weightKg != null ? String(pet.weightKg).replace('.', ',') : '',
    temperament: pet.temperament ?? '',
    healthNotes: pet.healthNotes ?? '',
    vaccinated: pet.vaccinated === true ? 'yes' : pet.vaccinated === false ? 'no' : '',
  }
}

/** Confere o que dá para conferir antes de enviar; a API repete as regras (e o banco também). Nunca repete o texto digitado. */
function validate(input: PetInput): Errors {
  const found: Errors = {}
  if (!input.name.trim()) found.name = 'Informe o nome do pet.'
  else if ([...input.name.trim()].length > PET_LIMITS.name) found.name = `No máximo ${PET_LIMITS.name} caracteres.`
  if (!input.species) found.species = 'Escolha cachorro ou gato.'
  if ([...input.breed.trim()].length > PET_LIMITS.breed) found.breed = `No máximo ${PET_LIMITS.breed} caracteres.`
  if ([...input.notes.trim()].length > PET_LIMITS.notes) found.notes = `No máximo ${PET_LIMITS.notes} caracteres.`
  if ([...input.healthNotes.trim()].length > PET_LIMITS.healthNotes) found.healthNotes = `No máximo ${PET_LIMITS.healthNotes} caracteres.`
  const weight = input.weightKg.trim() ? parseWeight(input.weightKg) : null
  if (input.weightKg.trim() && (weight === null || weight < 0.5 || weight > 120 || Math.round(weight * 10) / 10 !== weight)) found.weightKg = 'Informe o peso em kg, de 0,5 a 120 (por exemplo, 12,5).'
  return found
}

/**
 * Formulário de pet (cadastrar e editar). `onSubmit` faz o envio de verdade; aqui ficam a conferência, o bloqueio de envio duplo e a
 * apresentação dos erros da API (por campo quando a API aponta o campo; aviso geral nos demais casos). Ao cadastrar (sem `pet`), os
 * campos voltam ao vazio depois do sucesso.
 */
export function PetForm({
  idPrefix,
  pet,
  submitLabel,
  busyLabel,
  onSubmit,
  onCancel,
}: {
  idPrefix: string
  pet?: Pet
  submitLabel: string
  busyLabel: string
  onSubmit: (input: PetInput) => Promise<void>
  onCancel?: () => void
}) {
  const [input, setInput] = useState<PetInput>(pet ? fromPet(pet) : EMPTY)
  const [errors, setErrors] = useState<Errors>({})
  const [busy, setBusy] = useState(false)
  const once = useSingleFlight()

  function change(field: keyof PetInput, value: string) {
    setInput((current) => ({ ...current, [field]: value }))
    setErrors((current) => ({ ...current, [field]: undefined, form: undefined }))
  }

  async function submit(event: FormEvent) {
    event.preventDefault()
    await once(async () => {
      const found = validate(input)
      if (Object.keys(found).length > 0) {
        setErrors(found)
        return
      }
      setErrors({})
      setBusy(true)
      try {
        await onSubmit(input)
        if (!pet) setInput(EMPTY)
      } catch (e) {
        if (e instanceof ApiError && e.status === 400 && Object.keys(e.fieldErrors).length > 0) {
          const { name, species, breed, size, notes, coat, weightKg, temperament, healthNotes } = e.fieldErrors
          setErrors({ name, species, breed, size, notes, coat, weightKg, temperament, healthNotes })
        } else {
          setErrors({ form: describeError(e) })
        }
      } finally {
        setBusy(false)
      }
    })
  }

  const id = (field: string) => `${idPrefix}-${field}`
  return (
    <form onSubmit={submit} noValidate aria-label={pet ? `Editar ${pet.name}` : 'Cadastrar pet'}>
      <Field label="Nome do pet" name={id('name')} disabled={busy} maxLength={PET_LIMITS.name * 2} value={input.name} error={errors.name} onChange={(e) => change('name', e.target.value)} />
      <div className="field">
        <label htmlFor={id('species')}>Espécie</label>
        <select
          id={id('species')}
          name={id('species')}
          disabled={busy}
          value={input.species}
          aria-invalid={errors.species ? true : undefined}
          aria-describedby={errors.species ? id('species-error') : undefined}
          onChange={(e) => change('species', e.target.value)}
        >
          <option value="">Escolha…</option>
          {Object.entries(SPECIES_LABEL).map(([value, label]) => (
            <option key={value} value={value}>
              {label}
            </option>
          ))}
        </select>
        {errors.species && (
          <p id={id('species-error')} className="error">
            {errors.species}
          </p>
        )}
      </div>
      <Field label="Raça (opcional)" name={id('breed')} disabled={busy} maxLength={PET_LIMITS.breed * 2} value={input.breed} error={errors.breed} onChange={(e) => change('breed', e.target.value)} />
      <div className="field">
        <label htmlFor={id('size')}>Porte (opcional)</label>
        <select
          id={id('size')}
          name={id('size')}
          disabled={busy}
          value={input.size}
          aria-invalid={errors.size ? true : undefined}
          aria-describedby={errors.size ? id('size-error') : undefined}
          onChange={(e) => change('size', e.target.value)}
        >
          <option value="">Não informado</option>
          {Object.entries(SIZE_LABEL).map(([value, label]) => (
            <option key={value} value={value}>
              {label}
            </option>
          ))}
        </select>
        {errors.size && (
          <p id={id('size-error')} className="error">
            {errors.size}
          </p>
        )}
      </div>
      <p className="form-section">Ficha para o banho e a tosa</p>
      <SelectField label="Pelo (opcional)" name={id('coat')} value={input.coat} error={errors.coat} disabled={busy} empty="Não informado" options={COAT_LABEL} onChange={(v) => change('coat', v)} />
      <Field label="Peso em kg (opcional)" name={id('weightKg')} inputMode="decimal" disabled={busy} maxLength={8} value={input.weightKg} error={errors.weightKg} onChange={(e) => change('weightKg', e.target.value)} />
      <SelectField label="Comportamento (opcional)" name={id('temperament')} value={input.temperament} error={errors.temperament} disabled={busy} empty="Não informado" options={TEMPERAMENT_LABEL} onChange={(v) => change('temperament', v)} />
      <SelectField label="Vacinas em dia (opcional)" name={id('vaccinated')} value={input.vaccinated} disabled={busy} empty="Não informado" options={{ yes: 'Sim', no: 'Não' }} onChange={(v) => change('vaccinated', v)} />
      <div className="field">
        <label htmlFor={id('healthNotes')}>Saúde e alergias (opcional)</label>
        <textarea
          id={id('healthNotes')}
          name={id('healthNotes')}
          rows={2}
          disabled={busy}
          maxLength={PET_LIMITS.healthNotes * 2}
          value={input.healthNotes}
          aria-invalid={errors.healthNotes ? true : undefined}
          aria-describedby={errors.healthNotes ? id('healthNotes-error') : undefined}
          onChange={(e) => change('healthNotes', e.target.value)}
        />
        {errors.healthNotes && (
          <p id={id('healthNotes-error')} className="error">
            {errors.healthNotes}
          </p>
        )}
      </div>
      <div className="field">
        <label htmlFor={id('notes')}>Observações (opcional)</label>
        <textarea
          id={id('notes')}
          name={id('notes')}
          rows={3}
          disabled={busy}
          maxLength={PET_LIMITS.notes * 2}
          value={input.notes}
          aria-invalid={errors.notes ? true : undefined}
          aria-describedby={[id('notes-hint'), errors.notes ? id('notes-error') : ''].filter(Boolean).join(' ')}
          onChange={(e) => change('notes', e.target.value)}
        />
        <p id={id('notes-hint')} className="hint">
          O prestador vê estas observações no dia do atendimento. Até {PET_LIMITS.notes} caracteres.
        </p>
        {errors.notes && (
          <p id={id('notes-error')} className="error">
            {errors.notes}
          </p>
        )}
      </div>
      <p role="alert" className="error" hidden={!errors.form}>
        {errors.form}
      </p>
      <span className="actions">
        <button type="submit" className="button" disabled={busy}>
          {busy ? busyLabel : submitLabel}
        </button>
        {onCancel && (
          <button type="button" className="button secondary" disabled={busy} onClick={onCancel}>
            Cancelar
          </button>
        )}
      </span>
    </form>
  )
}
