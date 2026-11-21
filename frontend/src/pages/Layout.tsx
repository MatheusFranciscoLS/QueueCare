import { useEffect, useRef, useState } from 'react'
import { Link, NavLink, Outlet } from 'react-router-dom'
import { ROLE_LABEL } from '../auth/roles'
import { useAuth, type User } from '../auth/session'
import { Button } from '../ui/components'
import { ThemeToggle } from '../ui/ThemeToggle'
import '../ui/shell.css'
import { navigationFor } from './navigation'
import { useRouteFocus } from './useRouteFocus'
import { WakingBanner } from './WakingBanner'

function Brand({ onNavigate }: { onNavigate?: () => void }) {
  return (
    <Link to="/" className="pp-brand" onClick={onNavigate}>
      <span>
        Pelo <b>&amp;</b> Ponto
      </span>
    </Link>
  )
}

function Menu({ user, label, onNavigate }: { user: User; label: string; onNavigate?: () => void }) {
  const nav = navigationFor(user.role)
  return (
    <nav aria-label={label === '' ? nav.label : `${nav.label}${label}`} className="pp-nav">
      {nav.items.map((item) => (
        <NavLink key={item.to} to={item.to} end={item.end} onClick={onNavigate}>
          {item.label}
        </NavLink>
      ))}
    </nav>
  )
}

function Account({ user, onNavigate }: { user: User; onNavigate?: () => void }) {
  const { logout } = useAuth()
  return (
    <div className="pp-who">
      <div>
        <strong>{user.name}</strong>
        <span>{ROLE_LABEL[user.role]}</span>
      </div>
      {!user.mustChangePassword && (
        <>
          <Link to="/perfil" className="pp-who__link" onClick={onNavigate}>
            Meu perfil
          </Link>
          <Link to="/trocar-senha" className="pp-who__link" onClick={onNavigate}>
            Trocar senha
          </Link>
          <Link to="/privacidade" className="pp-who__link" onClick={onNavigate}>
            Privacidade
          </Link>
        </>
      )}
      <ThemeToggle />
      <Button variant="quiet" onClick={logout}>
        Sair
      </Button>
    </div>
  )
}

/** Casca do produto (ADR 0018 v2): barra lateral só de texto no computador e gaveta (dialog nativo) no celular, com atalho para o conteúdo. */
export function Layout() {
  const { user } = useAuth()
  const showNav = user !== null && !user.mustChangePassword
  const mainRef = useRef<HTMLElement>(null)
  const drawerRef = useRef<HTMLDialogElement>(null)
  const [drawerOpen, setDrawerOpen] = useState(false)
  useRouteFocus(mainRef)

  // A gaveta é um <dialog> modal: foco inicial e contido nela, fundo inerte e Esc de graça; ao fechar, o foco volta ao botão "Menu".
  useEffect(() => {
    const dialog = drawerRef.current
    if (!dialog) return
    if (drawerOpen && !dialog.open) {
      if (typeof dialog.showModal === 'function') dialog.showModal()
      else dialog.setAttribute('open', '')
    }
    if (!drawerOpen && dialog.open) {
      if (typeof dialog.close === 'function') dialog.close()
      else dialog.removeAttribute('open')
    }
  }, [drawerOpen])

  // Janela alargada com a gaveta aberta: a barra lateral reaparece, então a gaveta fecha.
  useEffect(() => {
    if (!drawerOpen || typeof window.matchMedia !== 'function') return
    const query = window.matchMedia('(min-width: 901px)')
    const close = () => query.matches && setDrawerOpen(false)
    query.addEventListener('change', close)
    return () => query.removeEventListener('change', close)
  }, [drawerOpen])

  if (!user) {
    return (
      <div className="pp pp-app">
        <a className="pp-skip" href="#conteudo">
          Pular para o conteúdo
        </a>
        <main id="conteudo" ref={mainRef} tabIndex={-1} className="pp-main pp-main--public">
          <WakingBanner />
          <Outlet />
        </main>
      </div>
    )
  }

  return (
    <div className="pp pp-app">
      <a className="pp-skip" href="#conteudo">
        Pular para o conteúdo
      </a>
      <div className="pp-frame">
        <header className="pp-rail">
          <Brand />
          {showNav && (
            <button type="button" className="pp-btn pp-menubtn" aria-haspopup="dialog" onClick={() => setDrawerOpen(true)}>
              Menu
            </button>
          )}
          {showNav && <Menu user={user} label="" />}
          {user && <Account user={user} />}
        </header>
        <main id="conteudo" ref={mainRef} tabIndex={-1} className="pp-main">
          <WakingBanner />
          <div className="pp-content">
            <Outlet />
          </div>
        </main>
      </div>
      <dialog
        ref={drawerRef}
        className="pp-drawer"
        aria-label="Menu"
        onClose={() => setDrawerOpen(false)}
        onClick={(e) => {
          if (e.target === e.currentTarget) setDrawerOpen(false)
        }}
      >
        {drawerOpen && user && (
          <>
            <div className="pp-drawer__head">
              <Brand onNavigate={() => setDrawerOpen(false)} />
              <Button variant="quiet" onClick={() => setDrawerOpen(false)}>
                Fechar
              </Button>
            </div>
            <Menu user={user} label=" (celular)" onNavigate={() => setDrawerOpen(false)} />
            <Account user={user} onNavigate={() => setDrawerOpen(false)} />
          </>
        )}
      </dialog>
    </div>
  )
}
