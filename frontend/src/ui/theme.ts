/** Tema do Pelo & Ponto: segue o sistema por padrão; o usuário pode forçar claro ou escuro (ADR 0018, seção 6). */
export type ThemePreference = 'system' | 'light' | 'dark'

export const THEME_KEY = 'pp-tema'

export function readThemePreference(): ThemePreference {
  try {
    const stored = window.localStorage.getItem(THEME_KEY)
    return stored === 'light' || stored === 'dark' ? stored : 'system'
  } catch {
    // Armazenamento bloqueado (janela privada, dados limpos): segue o sistema.
    return 'system'
  }
}

/** Aplica no <html>; "system" remove o atributo e deixa valer a preferência do sistema. */
export function applyTheme(preference: ThemePreference): void {
  const root = document.documentElement
  if (preference === 'system') root.removeAttribute('data-theme')
  else root.setAttribute('data-theme', preference)
}

export function saveThemePreference(preference: ThemePreference): void {
  try {
    if (preference === 'system') window.localStorage.removeItem(THEME_KEY)
    else window.localStorage.setItem(THEME_KEY, preference)
  } catch {
    // Sem armazenamento: a escolha vale só nesta página.
  }
}

export const NEXT_THEME: Record<ThemePreference, ThemePreference> = { system: 'light', light: 'dark', dark: 'system' }
export const THEME_LABEL: Record<ThemePreference, string> = { system: 'Tema: sistema', light: 'Tema: claro', dark: 'Tema: escuro' }
