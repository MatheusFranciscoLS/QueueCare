import { useState } from 'react'
import { Button } from './components'
import { applyTheme, NEXT_THEME, readThemePreference, saveThemePreference, THEME_LABEL, type ThemePreference } from './theme'

/** Alterna sistema, claro e escuro e guarda a escolha (com try/catch: sem armazenamento, vale só nesta página). */
export function ThemeToggle() {
  const [preference, setPreference] = useState<ThemePreference>(readThemePreference)
  return (
    <Button
      variant="quiet"
      onClick={() => {
        const next = NEXT_THEME[preference]
        setPreference(next)
        applyTheme(next)
        saveThemePreference(next)
      }}
    >
      {THEME_LABEL[preference]}
    </Button>
  )
}
