import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import { ApiError, apiRequest, type RequestOptions } from '../api/client'

export type Role = 'ADMIN' | 'PROVIDER' | 'ATTENDANT' | 'CUSTOMER'

export interface User {
  id: string
  name: string
  email: string
  role: Role
  mustChangePassword: boolean
  /** WhatsApp só com dígitos e DDI (ex.: 5511912345678); ausente se não informado. */
  phone?: string | null
}

interface TokenResponse {
  accessToken: string
  tokenType: string
  expiresIn: number
  mustChangePassword: boolean
}

interface Session {
  token: string
  user: User
}

export interface AuthApi {
  user: User | null
  /** A sessão acabou por expirar (e não porque a pessoa saiu); a tela de entrada avisa. */
  expired: boolean
  login(email: string, password: string): Promise<void>
  /** `privacyPolicyVersion`: a versão da política que a pessoa leu (ADR 0027), gravada no cadastro. */
  register(name: string, email: string, password: string, phone?: string, privacyPolicyVersion?: string): Promise<void>
  /** Troca o próprio WhatsApp (vazio remove) e atualiza a sessão. */
  updatePhone(phone: string): Promise<void>
  logout(): void
  changePassword(currentPassword: string, newPassword: string): Promise<void>
  /** Chamada autenticada à API. Um 401 encerra a sessão (token vencido, conta desativada ou senha trocada em outro lugar). */
  request<T>(path: string, options?: Omit<RequestOptions, 'token'>): Promise<T>
}

const AuthContext = createContext<AuthApi | null>(null)

/** Folga para sair um pouco ANTES do token vencer, em vez de falhar numa chamada. */
const EXPIRY_MARGIN_SECONDS = 5

/**
 * Sessão da pessoa logada. O token (JWT de 15 minutos) fica SÓ em memória: nunca em localStorage, sessionStorage nem cookie
 * (ADR 0008), então um script injetado não o encontra guardado. O custo é aceito: recarregar a página pede o login de novo.
 */
export function AuthProvider({ children }: { children: ReactNode }) {
  const [session, setSession] = useState<Session | null>(null)
  const [expired, setExpired] = useState(false)
  const tokenRef = useRef<string | null>(null)
  /**
   * Geração das operações de autenticação (entrar, cadastrar, trocar senha). Cada operação nova E todo encerramento de sessão
   * a incrementam; uma operação que termina depois (por exemplo, a consulta a /api/me que vem depois de um "Sair") vê que a
   * geração mudou e NÃO cria a sessão de volta.
   */
  const generation = useRef(0)
  const timer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined)

  const clear = useCallback((because: 'logout' | 'expired') => {
    clearTimeout(timer.current)
    generation.current += 1
    tokenRef.current = null
    setSession(null)
    setExpired(because === 'expired')
  }, [])

  const start = useCallback(
    (token: string, expiresInSeconds: number, user: User) => {
      clearTimeout(timer.current)
      tokenRef.current = token
      setExpired(false)
      setSession({ token, user })
      timer.current = setTimeout(() => clear('expired'), Math.max(1, expiresInSeconds - EXPIRY_MARGIN_SECONDS) * 1000)
    },
    [clear],
  )

  useEffect(() => () => clearTimeout(timer.current), [])

  const request = useCallback(
    async <T,>(path: string, options: Omit<RequestOptions, 'token'> = {}): Promise<T> => {
      const token = tokenRef.current
      try {
        return await apiRequest<T>(path, { ...options, token })
      } catch (e) {
        if (e instanceof ApiError && e.status === 401 && token && tokenRef.current === token) clear('expired')
        throw e
      }
    },
    [clear],
  )

  const enter = useCallback(
    async (response: TokenResponse, operation: number) => {
      const user = await apiRequest<User>('/api/me', { token: response.accessToken })
      if (generation.current !== operation) return // a pessoa saiu (ou começou outra operação) enquanto esperava: não reabre a sessão
      start(response.accessToken, response.expiresIn, user)
    },
    [start],
  )

  const api = useMemo<AuthApi>(
    () => ({
      user: session?.user ?? null,
      expired,
      async login(email, password) {
        const operation = ++generation.current
        await enter(await apiRequest<TokenResponse>('/api/auth/login', { method: 'POST', body: { email, password } }), operation)
      },
      async register(name, email, password, phone, privacyPolicyVersion) {
        const operation = ++generation.current
        const body = { name, email, password, ...(phone ? { phone } : {}), ...(privacyPolicyVersion ? { privacyPolicyVersion } : {}) }
        await apiRequest('/api/auth/register', { method: 'POST', body })
        await enter(await apiRequest<TokenResponse>('/api/auth/login', { method: 'POST', body: { email, password } }), operation)
      },
      async updatePhone(phone) {
        const updated = await request<User>('/api/me/phone', { method: 'PUT', body: { phone } })
        setSession((current) => (current ? { ...current, user: updated } : current))
      },
      logout: () => clear('logout'),
      async changePassword(currentPassword, newPassword) {
        // A troca invalida o token atual e devolve um novo: a sessão passa a usá-lo.
        const operation = ++generation.current
        await enter(await request<TokenResponse>('/api/auth/change-password', { method: 'POST', body: { currentPassword, newPassword } }), operation)
      },
      request,
    }),
    [session, expired, enter, clear, request],
  )

  return <AuthContext.Provider value={api}>{children}</AuthContext.Provider>
}

// eslint-disable-next-line react-refresh/only-export-components
export function useAuth(): AuthApi {
  const value = useContext(AuthContext)
  if (!value) throw new Error('useAuth precisa estar dentro de AuthProvider')
  return value
}
