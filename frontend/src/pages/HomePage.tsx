import { Link } from 'react-router-dom'
import { useAuth, type Role } from '../auth/session'

/** O que cada perfil fará nas próximas telas. Honesto: as telas ainda não existem, então nada aqui finge ser um botão. */
const COMING: Record<Role, string[]> = {
  CUSTOMER: [],
  PROVIDER: [],
  ATTENDANT: [],
  ADMIN: ['Criar contas de prestadores', 'Ativar, desativar e redefinir senhas de usuários'],
}

export function HomePage() {
  const { user } = useAuth()
  if (!user) return null
  return (
    <section className="card">
      <h1>Olá, {user.name}</h1>
      <p>Você entrou como {user.email}.</p>
      {user.role === 'PROVIDER' && (
        <>
          <h2>Configure o seu negócio</h2>
          <ol>
            <li>
              <Link to="/meu-negocio">Meu negócio</Link>: nome, fuso horário, antecedência e prazo de cancelamento
            </li>
            <li>
              <Link to="/servicos">Serviços</Link>: o que você oferece e quanto tempo leva
            </li>
            <li>
              <Link to="/expediente">Expediente</Link>: dias e horários de atendimento
            </li>
          </ol>
          <h2>No dia a dia</h2>
          <ul>
            <li>
              <Link to="/agenda">Agenda</Link>: os agendamentos do dia, com cancelamento
            </li>
            <li>
              <Link to="/equipe">Equipe</Link>: criar contas de atendentes, vincular e desativar
            </li>
          </ul>
        </>
      )}
      {user.role === 'CUSTOMER' && (
        <>
          <h2>No dia a dia</h2>
          <ul>
            <li>
              <Link to="/agendar">Agendar</Link>: escolha o prestador, o serviço, o dia e um horário livre
            </li>
            <li>
              <Link to="/meus-agendamentos">Meus agendamentos</Link>: veja e cancele os seus agendamentos
            </li>
          </ul>
        </>
      )}
      {user.role === 'ATTENDANT' && (
        <>
          <h2>No dia a dia</h2>
          <ul>
            <li>
              <Link to="/agenda">Agenda</Link>: os agendamentos do dia do prestador, com cancelamento
            </li>
          </ul>
        </>
      )}
      {COMING[user.role].length > 0 && <h2>Em construção para o seu perfil</h2>}
      {COMING[user.role].length > 0 && (
        <>
          <ul>
            {COMING[user.role].map((item) => (
              <li key={item}>{item}</li>
            ))}
          </ul>
          <p className="hint">Essas telas chegam nos próximos passos; a API correspondente já existe e está testada.</p>
        </>
      )}
    </section>
  )
}
