import { useEffect, useState } from 'react'

/** O evento que o Chrome/Edge/Android disparam quando o app pode ser instalado (não existe em tipos padrão do TypeScript). */
interface BeforeInstallPromptEvent extends Event {
  prompt(): Promise<void>
  userChoice: Promise<{ outcome: 'accepted' | 'dismissed' }>
}

/**
 * Instalar o app (ADR 0030). O navegador avisa com `beforeinstallprompt` quando é possível; guardamos o evento e a pessoa o dispara pelo botão do próprio app.
 * `available` é falso no iOS (que instala por "Adicionar à Tela de Início", sem evento), com o app já instalado ou quando o navegador não oferece.
 */
export function useInstallPrompt(): { available: boolean; install: () => Promise<void> } {
  const [event, setEvent] = useState<BeforeInstallPromptEvent | null>(null)
  useEffect(() => {
    const onPrompt = (e: Event) => {
      e.preventDefault() // o navegador não mostra a própria faixa: o botão do app é o convite
      setEvent(e as BeforeInstallPromptEvent)
    }
    const onInstalled = () => setEvent(null)
    window.addEventListener('beforeinstallprompt', onPrompt)
    window.addEventListener('appinstalled', onInstalled)
    return () => {
      window.removeEventListener('beforeinstallprompt', onPrompt)
      window.removeEventListener('appinstalled', onInstalled)
    }
  }, [])
  return {
    available: event !== null,
    async install() {
      if (!event) return
      await event.prompt()
      await event.userChoice.catch(() => undefined)
      setEvent(null) // o evento só vale uma vez
    },
  }
}

export function InstallButton({ onNavigate }: { onNavigate?: () => void }) {
  const { available, install } = useInstallPrompt()
  if (!available) return null
  return (
    <button
      type="button"
      className="pp-who__link pp-install"
      onClick={() => {
        onNavigate?.()
        void install()
      }}
    >
      Instalar o app
    </button>
  )
}
