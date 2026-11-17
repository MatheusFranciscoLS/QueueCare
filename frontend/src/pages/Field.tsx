import { useState, type ComponentPropsWithRef } from 'react'

interface FieldProps extends ComponentPropsWithRef<'input'> {
  label: string
  hint?: string
  error?: string | null
  /** Só em campo de senha: botão "Mostrar senha" para conferir o que foi digitado (o aparelho do celular erra tecla). */
  reveal?: boolean
}

/** Campo com rótulo, dica e erro ligados ao input (leitores de tela leem o erro junto com o campo). */
export function Field({ label, hint, error, id, reveal = false, ...input }: FieldProps) {
  const [shown, setShown] = useState(false)
  const base = id ?? input.name ?? label
  const hintId = `${base}-hint`
  const errorId = `${base}-error`
  return (
    <div className="field">
      <label htmlFor={base}>{label}</label>
      <div className={reveal ? 'field-reveal' : undefined}>
        <input
          id={base}
          aria-invalid={error ? true : undefined}
          aria-describedby={[hint ? hintId : '', error ? errorId : ''].filter(Boolean).join(' ') || undefined}
          {...input}
          type={reveal && shown ? 'text' : input.type}
        />
        {reveal && (
          <button type="button" className="field-reveal__button" aria-pressed={shown} aria-controls={base} onClick={() => setShown((s) => !s)}>
            {shown ? 'Ocultar' : 'Mostrar'}
            {' '}
            <span className="pp-sr">senha</span>
          </button>
        )}
      </div>
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
