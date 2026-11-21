import type { Role } from '../auth/session'

export interface NavItem {
  to: string
  label: string
  /** Só o endereço exato fica ativo (o Início "/" não pode ficar ativo em todas as telas). */
  end?: boolean
}

/** Navegação por perfil (ADR 0018, seção 4). O nome do menu é lido por leitores de tela e usado pelos testes. */
export function navigationFor(role: Role): { label: string; items: NavItem[] } {
  switch (role) {
    case 'PROVIDER':
      return {
        label: 'Menu do prestador',
        items: [
          { to: '/', label: 'Início', end: true },
          { to: '/agenda', label: 'Agenda' },
          { to: '/fila', label: 'Fila' },
          { to: '/clientes', label: 'Clientes' },
          { to: '/servicos', label: 'Serviços' },
          { to: '/expediente', label: 'Expediente' },
          { to: '/equipe', label: 'Equipe' },
          { to: '/meu-negocio', label: 'Meu negócio' },
        ],
      }
    case 'ATTENDANT':
      return {
        label: 'Menu do atendente',
        items: [
          { to: '/', label: 'Início', end: true },
          { to: '/agenda', label: 'Agenda' },
          { to: '/fila', label: 'Fila' },
          { to: '/clientes', label: 'Clientes' },
        ],
      }
    case 'CUSTOMER':
      return {
        label: 'Menu do cliente',
        items: [
          { to: '/', label: 'Início', end: true },
          { to: '/agendar', label: 'Agendar' },
          { to: '/meus-agendamentos', label: 'Meus agendamentos' },
          { to: '/pets', label: 'Meus pets' },
        ],
      }
    case 'ADMIN':
      return {
        label: 'Menu do administrador',
        items: [
          { to: '/admin/contas', label: 'Contas' },
          { to: '/admin/prestadores/novo', label: 'Novo prestador' },
        ],
      }
  }
}
