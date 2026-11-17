import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { renderApp } from './helpers'

describe('composição da entrada (quem não entrou)', () => {
  it('Entrar tem a marca como link para o início, o painel de exemplo escondido de leitores de tela e nenhum menu', async () => {
    const { container } = renderApp('/entrar')
    expect(await screen.findByRole('heading', { level: 1, name: 'Entrar' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /Pelo & Ponto/ })).toHaveAttribute('href', '/')
    expect(screen.queryByRole('navigation')).not.toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Pular para o conteúdo' })).toHaveAttribute('href', '#conteudo')
    const art = container.querySelector('.pp-auth__art')
    expect(art).toHaveAttribute('aria-hidden', 'true') // a ilustração do banho é decoração: leitor de tela não a lê
    expect(screen.getByRole('main')).toHaveAttribute('id', 'conteudo')
  })

  it('Cadastrar usa a mesma composição', async () => {
    const { container } = renderApp('/cadastro')
    expect(await screen.findByRole('heading', { level: 1, name: 'Criar conta de cliente' })).toBeInTheDocument()
    expect(container.querySelector('.pp-auth__hero')).not.toBeNull()
  })

  it('a página não encontrada de quem não entrou também usa a composição, com o caminho de volta', async () => {
    const { container } = renderApp('/nao-existe')
    expect(await screen.findByRole('heading', { level: 1, name: 'Página não encontrada' })).toBeInTheDocument()
    expect(container.querySelector('.pp-auth__hero')).not.toBeNull()
    expect(screen.getByRole('link', { name: 'Ir para o início' })).toHaveAttribute('href', '/')
  })
})

describe('"Mostrar senha"', () => {
  it('alterna entre ocultar e mostrar o que foi digitado, diz o estado e mantém o valor', async () => {
    const user = userEvent.setup()
    renderApp('/entrar')
    const senha = await screen.findByLabelText('Senha')
    await user.type(senha, 'abc')
    expect(senha).toHaveAttribute('type', 'password')

    const mostrar = screen.getByRole('button', { name: 'Mostrar senha' })
    expect(mostrar).toHaveAttribute('aria-pressed', 'false')
    expect(mostrar).toHaveAttribute('aria-controls', senha.id)
    await user.click(mostrar)
    expect(senha).toHaveAttribute('type', 'text')
    expect(senha).toHaveValue('abc')
    const ocultar = screen.getByRole('button', { name: 'Ocultar senha' })
    expect(ocultar).toHaveAttribute('aria-pressed', 'true')

    await user.click(ocultar)
    expect(senha).toHaveAttribute('type', 'password')
  })

  it('só os campos de senha ganham o botão (o e-mail não)', async () => {
    renderApp('/entrar')
    await screen.findByLabelText('E-mail')
    expect(screen.getAllByRole('button', { name: /senha$/ })).toHaveLength(1)
  })
})
