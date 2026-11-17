import { useSyncExternalStore } from 'react'
import { Button } from './components'
import { currentThemePreference, NEXT_THEME, setThemePreference, subscribeTheme, THEME_LABEL } from './theme'

/** Alterna sistema, claro e escuro. Vários botões (barra lateral e gaveta) mostram sempre o mesmo estado: todos leem o atributo do <html>. */
export function ThemeToggle() {
  const preference = useSyncExternalStore(subscribeTheme, currentThemePreference)
  return (
    <Button variant="quiet" onClick={() => setThemePreference(NEXT_THEME[preference])}>
      {THEME_LABEL[preference]}
    </Button>
  )
}
