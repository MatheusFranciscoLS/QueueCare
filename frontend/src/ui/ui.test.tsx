import { fireEvent, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useState } from 'react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { Button, Choice, Field, Notice, SlotButton, StateBadge, StateDot } from './components'
import { STATE_LABEL, type AppointmentState } from './states'
import { applyTheme, readThemePreference, saveThemePreference, THEME_KEY } from './theme'
import { ThemeToggle } from './ThemeToggle'

afterEach(() => {
  vi.restoreAllMocks()
  window.localStorage.clear()
  document.documentElement.removeAttribute('data-theme')
})

describe('Button', () => {
  it('em carregamento ignora cliques, mostra o texto de carregando e não perde o foco (não fica disabled)', async () => {
    const onClick = vi.fn()
    render(
      <Button loading loadingText="Salvando…" onClick={onClick}>
        Agendar
      </Button>,
    )
    const button = screen.getByRole('button', { name: 'Salvando…' })
    button.focus()
    fireEvent.click(button)
    fireEvent.click(button)
    expect(onClick).not.toHaveBeenCalled()
    expect(button).not.toBeDisabled()
    expect(button).toHaveAttribute('aria-busy', 'true')
    expect(button).toHaveAttribute('aria-disabled', 'true')
    expect(button).toHaveFocus()
  })

  it('fora do carregamento chama onClick e usa type=button por padrão', async () => {
    const onClick = vi.fn()
    render(<Button onClick={onClick}>Voltar</Button>)
    const button = screen.getByRole('button', { name: 'Voltar' })
    expect(button).toHaveAttribute('type', 'button')
    await userEvent.setup().click(button)
    expect(onClick).toHaveBeenCalledTimes(1)
    expect(button).not.toHaveAttribute('aria-busy')
  })

  it('duplo clique síncrono enquanto o pai alterna para carregando envia uma vez só', () => {
    const send = vi.fn()
    function Form() {
      const [loading, setLoading] = useState(false)
      return (
        <Button
          loading={loading}
          onClick={() => {
            send()
            setLoading(true)
          }}
        >
          Agendar
        </Button>
      )
    }
    render(<Form />)
    const button = screen.getByRole('button', { name: 'Agendar' })
    fireEvent.click(button)
    fireEvent.click(screen.getByRole('button'))
    expect(send).toHaveBeenCalledTimes(1)
  })
})

describe('Field', () => {
  it('liga rótulo, dica e erro ao campo; o erro é anunciado e marca o campo como inválido', () => {
    render(
      <Field label="Nome do pet" hint="Como o tutor chama o pet." error="Informe o nome do pet.">
        {(c) => <input {...c} />}
      </Field>,
    )
    const input = screen.getByLabelText('Nome do pet')
    expect(input).toHaveAttribute('aria-invalid', 'true')
    const alert = screen.getByRole('alert')
    expect(alert).toHaveTextContent('Informe o nome do pet.')
    const described = input.getAttribute('aria-describedby') ?? ''
    expect(described.split(' ')).toContain(alert.id)
    expect(described.split(' ')).toContain(screen.getByText('Como o tutor chama o pet.').id)
  })

  it('sem erro não marca inválido nem mostra alerta; sem dica nem erro não tem aria-describedby', () => {
    render(<Field label="Observações">{(c) => <textarea {...c} />}</Field>)
    const field = screen.getByLabelText('Observações')
    expect(field).not.toHaveAttribute('aria-invalid')
    expect(field).not.toHaveAttribute('aria-describedby')
    expect(screen.queryByRole('alert')).toBeNull()
  })
})

describe('Choice e SlotButton', () => {
  it('a linha inteira (rótulo) seleciona a opção e mostra o detalhe', async () => {
    render(<Choice type="radio" name="s" label="Banho" detail="45 min" />)
    const radio = screen.getByRole('radio', { name: /Banho/ })
    await userEvent.setup().click(screen.getByText('Banho'))
    expect(radio).toBeChecked()
    expect(screen.getByText('45 min')).toBeInTheDocument()
  })

  it('horário indisponível fica desabilitado e diz isso em texto; o selecionado usa aria-pressed', async () => {
    const onSelect = vi.fn()
    render(
      <>
        <SlotButton time="09:00" unavailable onSelect={onSelect} />
        <SlotButton time="09:45" selected onSelect={onSelect} />
        <SlotButton time="10:30" onSelect={onSelect} />
      </>,
    )
    const off = screen.getByRole('button', { name: '09:00 (indisponível)' })
    expect(off).toBeDisabled()
    expect(off).not.toHaveAttribute('aria-pressed')
    expect(screen.getByRole('button', { name: '09:45' })).toHaveAttribute('aria-pressed', 'true')
    expect(screen.getByRole('button', { name: '10:30' })).toHaveAttribute('aria-pressed', 'false')
    await userEvent.setup().click(screen.getByRole('button', { name: '10:30' }))
    expect(onSelect).toHaveBeenCalledTimes(1)
  })
})

describe('Estado e aviso', () => {
  it.each(Object.entries(STATE_LABEL) as [AppointmentState, string][])('o estado %s sempre tem rótulo em texto e o ponto é decorativo', (state, label) => {
    const { container } = render(
      <>
        <StateDot state={state} />
        <StateBadge state={state} />
      </>,
    )
    expect(screen.getByText(label)).toBeInTheDocument()
    expect(container.querySelector('.pp-dot')).toHaveAttribute('aria-hidden', 'true')
    expect(container.querySelector('.pp-dot')).toHaveAttribute('data-state', state)
  })

  it('aviso de erro e de alerta usa role alert; informação e sucesso usam status', () => {
    render(
      <>
        <Notice>Info</Notice>
        <Notice kind="success">Sucesso</Notice>
        <Notice kind="warning">Aviso</Notice>
        <Notice kind="error">Erro</Notice>
      </>,
    )
    expect(screen.getAllByRole('status').map((n) => n.textContent)).toEqual(['Info', 'Sucesso'])
    expect(screen.getAllByRole('alert').map((n) => n.textContent)).toEqual(['Aviso', 'Erro'])
  })
})

describe('Tema', () => {
  it('sem escolha segue o sistema; escolha salva claro ou escuro é lida; valor estranho cai em sistema', () => {
    expect(readThemePreference()).toBe('system')
    window.localStorage.setItem(THEME_KEY, 'dark')
    expect(readThemePreference()).toBe('dark')
    window.localStorage.setItem(THEME_KEY, 'laranja')
    expect(readThemePreference()).toBe('system')
  })

  it('se o armazenamento falhar (janela privada), ler segue o sistema e salvar não quebra', () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('bloqueado')
    })
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('bloqueado')
    })
    expect(readThemePreference()).toBe('system')
    expect(() => saveThemePreference('dark')).not.toThrow()
  })

  it('aplicar escuro põe data-theme no html; sistema remove o atributo', () => {
    applyTheme('dark')
    expect(document.documentElement).toHaveAttribute('data-theme', 'dark')
    applyTheme('system')
    expect(document.documentElement).not.toHaveAttribute('data-theme')
  })

  it('o botão gira sistema, claro, escuro, sistema, aplica no html e guarda a escolha', async () => {
    const user = userEvent.setup()
    render(<ThemeToggle />)
    await user.click(screen.getByRole('button', { name: 'Tema: sistema' }))
    expect(document.documentElement).toHaveAttribute('data-theme', 'light')
    expect(window.localStorage.getItem(THEME_KEY)).toBe('light')
    await user.click(screen.getByRole('button', { name: 'Tema: claro' }))
    expect(document.documentElement).toHaveAttribute('data-theme', 'dark')
    expect(window.localStorage.getItem(THEME_KEY)).toBe('dark')
    await user.click(screen.getByRole('button', { name: 'Tema: escuro' }))
    expect(document.documentElement).not.toHaveAttribute('data-theme')
    expect(window.localStorage.getItem(THEME_KEY)).toBeNull()
  })
})
