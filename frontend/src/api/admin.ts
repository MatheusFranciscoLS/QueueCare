import type { AuthApi } from '../auth/session'

export type AdminRole = 'ADMIN' | 'PROVIDER' | 'ATTENDANT' | 'CUSTOMER'

/** Conta como o ADMIN a vê: nunca traz senha nem hash. */
export interface AdminUser {
  id: string
  name: string
  email: string
  role: AdminRole
  active: boolean
  mustChangePassword: boolean
  createdAt: string
}

export interface AdminUsersQuery {
  q: string
  role: string
  page: number
  size: number
}

export interface ProviderAccountInput {
  name: string
  email: string
  initialPassword: string
  businessName: string
  timeZone: string
  minimumNoticeMinutes: number
  cancellationDeadlineMinutes: number
}

export interface CreatedProvider {
  userId: string
  name: string
  email: string
  providerId: string
  businessName: string
  mustChangePassword: boolean
}

type Request = AuthApi['request']

/** Administração de contas. Só o perfil ADMIN chega aqui (a API confere); aqui só se monta a requisição. */
export function adminApi(request: Request) {
  return {
    users: ({ q, role, page, size }: AdminUsersQuery) => {
      const params = new URLSearchParams()
      if (q.trim()) params.set('q', q.trim())
      if (role) params.set('role', role)
      params.set('page', String(page))
      params.set('size', String(size))
      return request<AdminUser[]>(`/api/admin/users?${params.toString()}`)
    },
    activate: (userId: string) => request<void>(`/api/admin/users/${encodeURIComponent(userId)}/activate`, { method: 'POST' }),
    deactivate: (userId: string) => request<void>(`/api/admin/users/${encodeURIComponent(userId)}/deactivate`, { method: 'POST' }),
    resetPassword: (userId: string, newInitialPassword: string) =>
      request<void>(`/api/admin/users/${encodeURIComponent(userId)}/reset-password`, { method: 'POST', body: { newInitialPassword } }),
    createProvider: (input: ProviderAccountInput) => request<CreatedProvider>('/api/admin/providers', { method: 'POST', body: input }),
  }
}
