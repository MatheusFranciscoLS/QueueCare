import { Link, NavLink, Outlet } from 'react-router-dom'
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
      {user && !user.mustChangePassword && user.role === 'PROVIDER' && (
        <nav className="menu" aria-label="Menu do prestador">
          <NavLink to="/meu-negocio">Meu negócio</NavLink>
          <NavLink to="/servicos">Serviços</NavLink>
          <NavLink to="/expediente">Expediente</NavLink>
          <NavLink to="/equipe">Equipe</NavLink>
          <NavLink to="/agenda">Agenda</NavLink>
          <NavLink to="/fila">Fila</NavLink>
        </nav>
      )}
      {user && !user.mustChangePassword && user.role === 'ADMIN' && (
        <nav className="menu" aria-label="Menu do administrador">
          <NavLink to="/admin/contas">Contas</NavLink>
          <NavLink to="/admin/prestadores/novo">Novo prestador</NavLink>
        </nav>
      )}
      {user && !user.mustChangePassword && user.role === 'CUSTOMER' && (
        <nav className="menu" aria-label="Menu do cliente">
          <NavLink to="/agendar">Agendar</NavLink>
          <NavLink to="/meus-agendamentos">Meus agendamentos</NavLink>
        </nav>
      )}
      {user && !user.mustChangePassword && user.role === 'ATTENDANT' && (
        <nav className="menu" aria-label="Menu do atendente">
          <NavLink to="/agenda">Agenda</NavLink>
          <NavLink to="/fila">Fila</NavLink>
        </nav>
      )}
      <main className="content">
        <Outlet />
      </main>
    </div>
  )
}
