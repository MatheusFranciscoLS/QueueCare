import type { Role } from './session'

export const ROLE_LABEL: Record<Role, string> = {
  ADMIN: 'Administrador',
  PROVIDER: 'Prestador',
  ATTENDANT: 'Atendente',
  CUSTOMER: 'Cliente',
}
