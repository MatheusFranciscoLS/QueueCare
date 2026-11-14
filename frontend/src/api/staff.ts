import type { AuthApi } from '../auth/session'

export interface StaffMember {
  userId: string
  name: string
  email: string
}

type Request = AuthApi['request']

/** Equipe do prestador. A API decide tudo (perfil fixo ATTENDANT, vínculo ao PRÓPRIO negócio): aqui só se monta a requisição. */
export function staffApi(request: Request) {
  const base = '/api/provider/staff'
  return {
    list: () => request<StaffMember[]>(base),
    link: (email: string) => request<StaffMember>(base, { method: 'POST', body: { email } }),
    createAccount: (input: { name: string; email: string; initialPassword: string }) => request<StaffMember>(`${base}/accounts`, { method: 'POST', body: input }),
    deactivate: (userId: string) => request<void>(`${base}/${encodeURIComponent(userId)}/deactivate`, { method: 'POST' }),
    unlink: (userId: string) => request<void>(`${base}/${encodeURIComponent(userId)}`, { method: 'DELETE' }),
  }
}
