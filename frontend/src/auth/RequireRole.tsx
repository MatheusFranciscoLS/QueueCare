import { Navigate, Outlet } from 'react-router-dom'
import type { Role } from './session'
import { useAuth } from './session'

/** Telas de um perfil: quem tem outro perfil volta ao início. (A API continua sendo quem autoriza; isto só evita telas que não servem.) */
export function RequireRole({ roles }: { roles: Role[] }) {
  const { user } = useAuth()
  if (!user || !roles.includes(user.role)) return <Navigate to="/" replace />
  return <Outlet />
}
