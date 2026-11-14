import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { Loading } from '../pages/Loading'

describe('Loading', () => {
  it('anuncia o texto como status e esconde os pontos decorativos dos leitores de tela', () => {
    render(<Loading>Carregando horários…</Loading>)
    const status = screen.getByRole('status')
    expect(status).toHaveTextContent('Carregando horários…')
    expect(status.querySelector('.loading-dots')).toHaveAttribute('aria-hidden', 'true')
  })

  it('usa "Carregando…" quando nenhum texto é dado', () => {
    render(<Loading />)
    expect(screen.getByRole('status')).toHaveTextContent('Carregando…')
  })
})
