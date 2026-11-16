import { mkdirSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'
import type { Page } from '@playwright/test'

/** Contas FICTÍCIAS do ambiente de demonstração (backend/src/test/java/com/queuecare/dev/LocalDevServer.java). */
export const PASSWORD = ['Senha', 'Demo', '12345'].join('-')
export const ACCOUNTS = {
  admin: 'admin@pelo.test',
  prestador: 'helena@pelo.test',
  atendente: 'bruno@pelo.test',
  cliente: 'marina@pelo.test',
  clienteNovo: 'paulo@pelo.test',
} as const
export type Perfil = keyof typeof ACCOUNTS

/** Uma tela a verificar: o perfil que a vê, o endereço, e um nome para os arquivos. `publica` = sem login. */
export interface Tela {
  id: string
  perfil: Perfil | 'publica'
  path: string
  /** Nome do item do menu que leva à tela (a navegação é pelo menu: recarregar a página perderia o token, que fica só em memória). */
  menu?: string
  /** Título (h1) da tela: só existe quando ela terminou de carregar (evita fotografar o "Carregando…" ou a tela anterior). */
  pronto: string | RegExp
}

export const TELAS: Tela[] = [
  { id: 'entrar', perfil: 'publica', path: '/entrar', pronto: 'Entrar' },
  { id: 'cadastro', perfil: 'publica', path: '/cadastro', pronto: 'Criar conta de cliente' },
  { id: 'cliente-inicio', perfil: 'cliente', path: '/', pronto: /Olá, Marina/ },
  { id: 'cliente-agendar', perfil: 'cliente', path: '/agendar', menu: 'Agendar', pronto: 'Agendar' },
  { id: 'cliente-meus-agendamentos', perfil: 'cliente', path: '/meus-agendamentos', menu: 'Meus agendamentos', pronto: 'Meus agendamentos' },
  { id: 'cliente-pets', perfil: 'cliente', path: '/pets', menu: 'Meus pets', pronto: 'Meus pets' },
  { id: 'cliente-novo-pets-vazio', perfil: 'clienteNovo', path: '/pets', menu: 'Meus pets', pronto: 'Meus pets' },
  { id: 'prestador-inicio', perfil: 'prestador', path: '/', pronto: /Olá, Helena/ },
  { id: 'prestador-agenda', perfil: 'prestador', path: '/agenda', menu: 'Agenda', pronto: 'Agenda' },
  { id: 'prestador-fila', perfil: 'prestador', path: '/fila', menu: 'Fila', pronto: 'Fila' },
  { id: 'prestador-servicos', perfil: 'prestador', path: '/servicos', menu: 'Serviços', pronto: 'Serviços' },
  { id: 'prestador-expediente', perfil: 'prestador', path: '/expediente', menu: 'Expediente', pronto: 'Expediente' },
  { id: 'prestador-equipe', perfil: 'prestador', path: '/equipe', menu: 'Equipe', pronto: 'Equipe' },
  { id: 'prestador-meu-negocio', perfil: 'prestador', path: '/meu-negocio', menu: 'Meu negócio', pronto: /Meu negócio|Cadastre o seu negócio/ },
  { id: 'atendente-agenda', perfil: 'atendente', path: '/agenda', menu: 'Agenda', pronto: 'Agenda' },
  { id: 'admin-contas', perfil: 'admin', path: '/admin/contas', menu: 'Contas', pronto: 'Contas' },
  { id: 'admin-novo-prestador', perfil: 'admin', path: '/admin/prestadores/novo', menu: 'Novo prestador', pronto: 'Novo prestador' },
]

export const VIEWPORTS = {
  desktop: { width: 1280, height: 800 },
  celular: { width: 390, height: 844 },
} as const
export const LARGURAS_ROLAGEM = [320, 390, 768, 1024, 1280] as const
export const TEMAS = ['light', 'dark'] as const
export type Tema = (typeof TEMAS)[number]

export const SAIDA = join(import.meta.dirname, 'saida')
export function pasta(...partes: string[]): string {
  const dir = join(SAIDA, ...partes)
  mkdirSync(dir, { recursive: true })
  return dir
}
export function gravarJson(nome: string, dados: unknown): void {
  pasta()
  writeFileSync(join(SAIDA, nome), JSON.stringify(dados, null, 2))
}

/** Entra pela interface (o token fica só em memória, então não dá para pular o login). */
export async function entrar(page: Page, perfil: Perfil): Promise<void> {
  await page.goto('/entrar')
  await page.getByLabel('E-mail').fill(ACCOUNTS[perfil])
  await page.getByLabel('Senha').fill(PASSWORD)
  await page.getByRole('button', { name: 'Entrar' }).click()
  await page.getByRole('heading', { level: 1 }).first().waitFor()
}

/** Vai a outra rota SEM recarregar a página (recarregar perderia o token em memória). */
export async function irPara(page: Page, path: string): Promise<void> {
  await page.evaluate((to) => {
    // O React Router lê { usr, key, idx } do estado do histórico no popstate: sem eles ele ignora a navegação.
    const idx = (window.history.state?.idx ?? 0) + 1
    window.history.pushState({ usr: null, key: 'e2e' + idx, idx }, '', to)
    window.dispatchEvent(new PopStateEvent('popstate', { state: window.history.state }))
  }, path)
}

/** Abre a tela como o perfil dela (entra se preciso), com tema e tamanho de janela, e espera o conteúdo aparecer. */
export async function abrirTela(page: Page, tela: Tela, tema: Tema, viewport: { width: number; height: number }): Promise<void> {
  await page.setViewportSize(viewport)
  await page.emulateMedia({ colorScheme: tema, reducedMotion: 'reduce' })
  if (tela.perfil === 'publica') {
    await page.goto(tela.path)
  } else {
    await entrar(page, tela.perfil)
    if (tela.menu) await page.getByRole('navigation').getByRole('link', { name: tela.menu, exact: true }).first().click()
    else if (tela.path !== '/') await irPara(page, tela.path)
  }
  await page.getByRole('heading', { level: 1, name: tela.pronto, exact: typeof tela.pronto === 'string' }).first().waitFor({ timeout: 30_000 })
  // O conteúdo carregado depois do título (listas, horários) também precisa ter chegado: espera o fim das requisições.
  await page.waitForLoadState('networkidle')
  await page.waitForTimeout(250) // o fim das animações de entrada
}
