import { useAuth, type Role } from '../auth/session'

/** O que cada perfil fará nas próximas telas. Honesto: as telas ainda não existem, então nada aqui finge ser um botão. */
const COMING: Record<Role, string[]> = {
  CUSTOMER: ['Escolher um prestador e um horário livre', 'Ver e cancelar os seus agendamentos'],
  PROVIDER: ['Configurar o negócio, os serviços e o expediente', 'Cuidar da equipe de atendentes', 'Ver a agenda do dia'],
  ATTENDANT: ['Ver a agenda do dia do prestador', 'Cancelar agendamentos'],
  ADMIN: ['Criar contas de prestadores', 'Ativar, desativar e redefinir senhas de usuários'],
}

export function HomePage() {
  const { user } = useAuth()
  if (!user) return null
  return (
    <section className="card">
      <h1>Olá, {user.name}</h1>
      <p>Você entrou como {user.email}.</p>
      <h2>Em construção para o seu perfil</h2>
      <ul>
        {COMING[user.role].map((item) => (
          <li key={item}>{item}</li>
        ))}
      </ul>
      <p className="hint">Essas telas chegam nos próximos passos; a API correspondente já existe e está testada.</p>
    </section>
  )
}
