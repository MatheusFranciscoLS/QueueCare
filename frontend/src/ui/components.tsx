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

// ---------- Fio (linha do tempo do dia) ----------
/** A lista do dia: cada item mostra a hora, o ponto do estado e o conteúdo; o fio liga os itens (pontilhado enquanto falta, cheio quando termina). */
export function Fio({ children, label }: { children: ReactNode; label?: string }) {
  return (
    <ol className="pp-fio" aria-label={label}>
      {children}
    </ol>
  )
}

interface FioItemProps {
  /** Hora de início, como "10:30". */
  time: string
  state: AppointmentState
  children: ReactNode
}

/** O estado vem do status real do atendimento (nunca da hora). O rótulo do estado é lido junto da hora por leitores de tela. */
export function FioItem({ time, state, children }: FioItemProps) {
  return (
    <li className="pp-fio__item" data-state={state}>
      <div className="pp-fio__time">
        {time}
        <span className="pp-sr"> ({STATE_LABEL[state].toLowerCase()})</span>
      </div>
      <div className="pp-fio__node">
        <StateDot state={state} />
      </div>
      <div className="pp-fio__body">{children}</div>
    </li>
  )
}

// ---------- Resumo do dia, em frase ----------
/** Uma frase com os números em destaque (em vez de blocos de números). Use <strong> nos trechos que importam. */
export function Summary({ children }: { children: ReactNode }) {
  return <p className="pp-summary">{children}</p>
}

// ---------- Controle segmentado (Dia / Semana) ----------
interface SegmentedProps<T extends string> {
  label: string
  value: T
  options: { value: T; label: string }[]
  onChange: (value: T) => void
}

/** Escolha de uma opção entre poucas (ex.: Dia e Semana). Cada botão diz se está selecionado (aria-pressed) e tem 44 px de altura. */
export function Segmented<T extends string>({ label, value, options, onChange }: SegmentedProps<T>) {
  return (
    <div className="pp-seg" role="group" aria-label={label}>
      {options.map((option) => (
        <button key={option.value} type="button" aria-pressed={option.value === value} onClick={() => onChange(option.value)}>
          {option.label}
        </button>
      ))}
    </div>
  )
}

// ---------- Avatar do pet ----------
/**
 * O pet como protagonista: um círculo suave com o desenho da espécie (cachorro ou gato). Decorativo (o nome do pet está sempre ao lado).
 * Quando o sistema guardar a foto do pet, ela entra neste mesmo lugar.
 */
export function PetAvatar({ species, size = 'md' }: { species: string; size?: 'sm' | 'md' }) {
  const cat = species === 'CAT'
  return (
    <span className="pp-avatar" data-size={size} aria-hidden="true">
      <svg viewBox="0 0 24 24" focusable="false">
        {cat ? (
          <>
            <path d="M4.5 3.5 9 7.2H7L4 10.5Zm15 0L15 7.2h2l3 3.3Z" fill="currentColor" />
            <circle cx="12" cy="13" r="7.2" fill="currentColor" />
            <ellipse cx="9.2" cy="12" rx="1.1" ry="1.5" fill="var(--pp-surface)" />
            <ellipse cx="14.8" cy="12" rx="1.1" ry="1.5" fill="var(--pp-surface)" />
            <path d="M10.8 15.4h2.4L12 16.6Z" fill="var(--pp-surface)" />
          </>
        ) : (
          <>
            <path d="M5 4c-1.6 0-2.8 1.5-2.8 3.4 0 1.4.6 2.6 1.6 3.2L5 8.5C5 6 5.2 4 5 4Zm14 0c1.6 0 2.8 1.5 2.8 3.4 0 1.4-.6 2.6-1.6 3.2L19 8.5C19 6 18.8 4 19 4Z" fill="currentColor" />
            <circle cx="12" cy="12.5" r="7" fill="currentColor" />
            <circle cx="9.3" cy="11.5" r="1.1" fill="var(--pp-surface)" />
            <circle cx="14.7" cy="11.5" r="1.1" fill="var(--pp-surface)" />
            <ellipse cx="12" cy="15" rx="2.2" ry="1.5" fill="var(--pp-surface)" />
          </>
        )}
      </svg>
    </span>
  )
}
