import { Link, Outlet } from 'react-router-dom'
import { ROLE_LABEL } from '../auth/roles'
import { useAuth } from '../auth/session'

export function Layout() {
  const { user, logout } = useAuth()
  return (
    <div className="shell">
      <header className="topbar">
        <Link to="/" className="brand">
          QueueCare
        </Link>
        {user && (
          <div className="who">
            <span>
              {user.name} <span className="badge">{ROLE_LABEL[user.role]}</span>
            </span>
            {!user.mustChangePassword && (
              <Link to="/trocar-senha" className="link">
                Trocar senha
              </Link>
            )}
            <button type="button" className="button secondary" onClick={logout}>
              Sair
            </button>
          </div>
        )}
      </header>
      <main className="content">
        <Outlet />
      </main>
    </div>
  )
}
