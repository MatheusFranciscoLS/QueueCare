import type { ComponentPropsWithRef } from 'react'

interface FieldProps extends ComponentPropsWithRef<'input'> {
  label: string
  hint?: string
  error?: string | null
}

/** Campo com rótulo, dica e erro ligados ao input (leitores de tela leem o erro junto com o campo). */
export function Field({ label, hint, error, id, ...input }: FieldProps) {
  const base = id ?? input.name ?? label
  const hintId = `${base}-hint`
  const errorId = `${base}-error`
  return (
    <div className="field">
      <label htmlFor={base}>{label}</label>
      <input
        id={base}
        aria-invalid={error ? true : undefined}
        aria-describedby={[hint ? hintId : '', error ? errorId : ''].filter(Boolean).join(' ') || undefined}
        {...input}
      />
      {hint && (
        <p id={hintId} className="hint">
          {hint}
        </p>
      )}
      {error && (
        <p id={errorId} className="error">
          {error}
        </p>
      )}
    </div>
  )
}
