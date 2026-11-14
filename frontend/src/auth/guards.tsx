import { Navigate, Outlet, useLocation } from 'react-router-dom'
import { useAuth } from './session'

/** Só para quem está logado. Com troca de senha pendente, a única tela aberta é a de troca (a API também bloqueia o resto). */
export function RequireAuth() {
  const { user } = useAuth()
  const location = useLocation()
  if (!user) return <Navigate to="/entrar" replace state={{ from: location.pathname }} />
  if (user.mustChangePassword && location.pathname !== '/trocar-senha') return <Navigate to="/trocar-senha" replace />
  return <Outlet />
}

/** Telas de entrada e cadastro: quem já está logado vai para o início. */
export function PublicOnly() {
  const { user } = useAuth()
  if (user) return <Navigate to={user.mustChangePassword ? '/trocar-senha' : '/'} replace />
  return <Outlet />
}
