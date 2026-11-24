import type { AuthApi } from '../auth/session'

/** Versão da Política de Privacidade (AAAA-MM-DD): vai no cadastro, para ficar registrado QUAL texto a pessoa leu (ADR 0027). Muda junto com o texto da página. */
export const PRIVACY_POLICY_VERSION = '2026-11-24'

type Request = AuthApi['request']

/** Direitos do tutor sobre os próprios dados (ADR 0027): baixar tudo e excluir a conta (que é anonimizada). */
export function privacyApi(request: Request) {
  return {
    exportData: () => request<unknown>('/api/me/export'),
    /** Exige a senha atual; a conta sai do ar na hora e a sessão acaba. */
    deleteAccount: (password: string) => request<void>('/api/me/delete-account', { method: 'POST', body: { password } }),
  }
}

/** Entrega o conteúdo como arquivo .json no aparelho (sem passar por servidor nenhum: os dados já estão aqui). */
export function downloadJson(filename: string, data: unknown): void {
  const blob = new Blob([JSON.stringify(data, null, 2)], { type: 'application/json' })
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = filename
  document.body.appendChild(link)
  link.click()
  link.remove()
  URL.revokeObjectURL(url)
}
