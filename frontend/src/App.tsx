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
