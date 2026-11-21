import { Link } from 'react-router-dom'
import { useAuth, type Role } from '../auth/session'
import { CustomerPanel } from './customer/CustomerPanel'
import { SetupPanel } from './provider/SetupPanel'
import { TodaySummary } from './TodaySummary'

/** O que cada perfil fará nas próximas telas. Honesto: as telas ainda não existem, então nada aqui finge ser um botão. */
const COMING: Record<Role, string[]> = {
  CUSTOMER: [],
  PROVIDER: [],
  ATTENDANT: [],
  ADMIN: [],
}

export function HomePage() {
  const { user } = useAuth()
  if (!user) return null
  return (
    <section className="card">
      <h1>Olá, {user.name}</h1>
      <p className="hint">Você entrou como {user.email}.</p>
      {(user.role === 'PROVIDER' || user.role === 'ATTENDANT') && <TodaySummary />}
      {user.role === 'PROVIDER' && (
        <>
          <SetupPanel />
          <h2>No dia a dia</h2>
          <ul className="shortcuts">
            <li>
              <Link to="/agenda">Agenda</Link>
              <span>os agendamentos do dia, com chegada, conclusão e cancelamento</span>
            </li>
            <li>
              <Link to="/fila">Fila</Link>
              <span>quem já chegou e espera, por ordem de chegada</span>
            </li>
            <li>
              <Link to="/equipe">Equipe</Link>
              <span>criar contas de atendentes, vincular e desativar</span>
            </li>
          </ul>
        </>
      )}
      {user.role === 'ADMIN' && (
        <>
          <h2>No dia a dia</h2>
          <ul className="shortcuts">
            <li>
              <Link to="/admin/contas">Contas</Link>
              <span>buscar, ativar, desativar e redefinir senhas</span>
            </li>
            <li>
              <Link to="/admin/prestadores/novo">Novo prestador</Link>
              <span>criar a conta e o negócio de um prestador</span>
            </li>
          </ul>
        </>
      )}
      {user.role === 'CUSTOMER' && (
        <>
          <CustomerPanel />
          {!user.phone && (
            <p className="notice">
              Falta o seu WhatsApp: é por ele que o estúdio avisa quando o seu pet fica pronto. <Link to="/perfil">Adicionar WhatsApp</Link>
            </p>
          )}
          <h2>No dia a dia</h2>
          <ul className="shortcuts">
            <li className="primary">
              <Link to="/agendar">Agendar</Link>
              <span>escolha o prestador, o serviço, o dia e um horário livre</span>
            </li>
            <li>
              <Link to="/meus-agendamentos">Meus agendamentos</Link>
              <span>veja e cancele os seus agendamentos</span>
            </li>
            <li>
              <Link to="/pets">Meus pets</Link>
              <span>cadastre, edite e arquive os animais que você leva ao estúdio</span>
            </li>
          </ul>
        </>
      )}
      {user.role === 'ATTENDANT' && (
        <>
          <h2>No dia a dia</h2>
          <ul className="shortcuts">
            <li>
              <Link to="/agenda">Agenda</Link>
              <span>os agendamentos do dia do prestador, com chegada, conclusão e cancelamento</span>
            </li>
            <li>
              <Link to="/fila">Fila</Link>
              <span>quem já chegou e espera, por ordem de chegada</span>
            </li>
          </ul>
        </>
      )}
      {COMING[user.role].length > 0 && <h2>Em construção para o seu perfil</h2>}
      {COMING[user.role].length > 0 && (
        <>
          <ul className="shortcuts">
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
