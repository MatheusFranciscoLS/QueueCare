import { useId, type ButtonHTMLAttributes, type InputHTMLAttributes, type ReactNode } from 'react'
import { STATE_LABEL, type AppointmentState } from './states'
import './ui.css'

/** Componentes base do Pelo & Ponto (ADR 0018 v2). Só apresentação: nenhuma regra de negócio mora aqui. */

// ---------- Button ----------
export type ButtonVariant = 'primary' | 'gold' | 'secondary' | 'quiet' | 'danger'

interface ButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: ButtonVariant
  /** Enquanto verdadeiro, o clique é ignorado (sem envio duplo) e o botão mostra loadingText. O botão NÃO fica disabled: o foco não se perde. */
  loading?: boolean
  loadingText?: string
}

export function Button({ variant = 'secondary', loading = false, loadingText = 'Salvando…', className, children, onClick, type = 'button', ...rest }: ButtonProps) {
  const cls = ['pp-btn', variant === 'secondary' ? '' : `pp-btn--${variant}`, className].filter(Boolean).join(' ')
  return (
    <button
      {...rest}
      type={type}
      className={cls}
      aria-disabled={loading || rest['aria-disabled'] ? true : undefined}
      aria-busy={loading || undefined}
      onClick={(e) => {
        if (loading) {
          e.preventDefault()
          return
        }
        onClick?.(e)
      }}
    >
      {loading ? loadingText : children}
    </button>
  )
}

// ---------- Field ----------
export interface FieldControlProps {
  id: string
  'aria-describedby'?: string
  'aria-invalid'?: true
}

interface FieldProps {
  label: string
  hint?: string
  /** Mensagem de erro (nunca repete o valor digitado). Ligada ao campo e anunciada a leitores de tela. */
  error?: string
  children: (control: FieldControlProps) => ReactNode
}

export function Field({ label, hint, error, children }: FieldProps) {
  const id = useId()
  const hintId = `${id}-hint`
  const errorId = `${id}-erro`
  const describedBy = [hint ? hintId : null, error ? errorId : null].filter(Boolean).join(' ') || undefined
  return (
    <div className="pp-field" data-invalid={error ? 'true' : undefined}>
      <label className="pp-label" htmlFor={id}>
        {label}
      </label>
      {children({ id, 'aria-describedby': describedBy, 'aria-invalid': error ? true : undefined })}
      {hint && (
        <p className="pp-hint" id={hintId}>
          {hint}
        </p>
      )}
      {error && (
        <p className="pp-error" id={errorId} role="alert">
          {error}
        </p>
      )}
    </div>
  )
}

// ---------- Opção (rádio ou caixa) ----------
interface ChoiceProps extends Omit<InputHTMLAttributes<HTMLInputElement>, 'type'> {
  type: 'radio' | 'checkbox'
  label: string
  detail?: string
}

export function Choice({ label, detail, ...input }: ChoiceProps) {
  return (
    <label className="pp-opt">
      <input {...input} />
      <span>
        {label}
        {detail && <small>{detail}</small>}
      </span>
    </label>
  )
}

// ---------- Horário livre ----------
interface SlotProps {
  time: string
  selected?: boolean
  unavailable?: boolean
  onSelect?: () => void
}

export function SlotButton({ time, selected = false, unavailable = false, onSelect }: SlotProps) {
  return (
    <button type="button" className="pp-slot" aria-pressed={unavailable ? undefined : selected} disabled={unavailable} onClick={onSelect}>
      {time}
      {unavailable && (
        <>
          {' '}
          <span className="pp-sr">(indisponível)</span>
        </>
      )}
    </button>
  )
}

// ---------- Estado do atendimento ----------
/** O ponto é só forma (decorativo): o texto do estado sempre acompanha, nunca só a cor ou a forma. */
export function StateDot({ state }: { state: AppointmentState }) {
  return <span className="pp-dot" data-state={state} aria-hidden="true" />
}

export function StateBadge({ state, children }: { state: AppointmentState; children?: ReactNode }) {
  return (
    <span className="pp-badge" data-state={state}>
      {children ?? STATE_LABEL[state]}
    </span>
  )
}

// ---------- Aviso ----------
export type NoticeKind = 'info' | 'success' | 'warning' | 'error'

/** Erro e aviso interrompem (role alert); informação e sucesso são educados (role status). */
export function Notice({ kind = 'info', children }: { kind?: NoticeKind; children: ReactNode }) {
  const urgent = kind === 'error' || kind === 'warning'
  return (
    <div className="pp-notice" data-kind={kind} role={urgent ? 'alert' : 'status'}>
      <p>{children}</p>
    </div>
  )
}
