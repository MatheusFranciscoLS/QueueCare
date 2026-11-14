import { Route, Routes } from 'react-router-dom'
import { PublicOnly, RequireAuth } from './auth/guards'
import { RequireRole } from './auth/RequireRole'
import { ChangePasswordPage } from './pages/ChangePasswordPage'
import { HomePage } from './pages/HomePage'
import { Layout } from './pages/Layout'
import { LoginPage } from './pages/LoginPage'
import { AvailabilityPage } from './pages/provider/AvailabilityPage'
import { BusinessPage } from './pages/provider/BusinessPage'
import { ServicesPage } from './pages/provider/ServicesPage'
import { CreateProviderPage } from './pages/admin/CreateProviderPage'
import { UsersPage } from './pages/admin/UsersPage'
import { AgendaPage } from './pages/agenda/AgendaPage'
import { QueuePage } from './pages/agenda/QueuePage'
import { BookPage } from './pages/customer/BookPage'
import { MyAppointmentsPage } from './pages/customer/MyAppointmentsPage'
import { TeamPage } from './pages/provider/TeamPage'
import { RegisterPage } from './pages/RegisterPage'

export function App() {
  return (
    <Routes>
      <Route element={<Layout />}>
        <Route element={<PublicOnly />}>
          <Route path="/entrar" element={<LoginPage />} />
          <Route path="/cadastro" element={<RegisterPage />} />
        </Route>
        <Route element={<RequireAuth />}>
          <Route path="/" element={<HomePage />} />
          <Route path="/trocar-senha" element={<ChangePasswordPage />} />
          <Route element={<RequireRole roles={['PROVIDER']} />}>
            <Route path="/meu-negocio" element={<BusinessPage />} />
            <Route path="/servicos" element={<ServicesPage />} />
            <Route path="/expediente" element={<AvailabilityPage />} />
            <Route path="/equipe" element={<TeamPage />} />
          </Route>
          <Route element={<RequireRole roles={['ADMIN']} />}>
            <Route path="/admin/contas" element={<UsersPage />} />
            <Route path="/admin/prestadores/novo" element={<CreateProviderPage />} />
          </Route>
          <Route element={<RequireRole roles={['CUSTOMER']} />}>
            <Route path="/agendar" element={<BookPage />} />
            <Route path="/meus-agendamentos" element={<MyAppointmentsPage />} />
          </Route>
          <Route element={<RequireRole roles={['PROVIDER', 'ATTENDANT']} />}>
            <Route path="/agenda" element={<AgendaPage />} />
            <Route path="/fila" element={<QueuePage />} />
          </Route>
        </Route>
        <Route path="*" element={<NotFound />} />
      </Route>
    </Routes>
  )
}

function NotFound() {
  return (
    <section className="card narrow">
      <h1>Página não encontrada</h1>
    </section>
  )
}
