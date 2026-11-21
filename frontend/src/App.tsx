import { lazy, Suspense, type ComponentType, type ReactNode } from 'react'
import { Link, Route, Routes } from 'react-router-dom'
import { useAuth } from './auth/session'
import { AuthLayout } from './pages/AuthLayout'
import { PublicOnly, RequireAuth } from './auth/guards'
import { RequireRole } from './auth/RequireRole'
import { ChangePasswordPage } from './pages/ChangePasswordPage'
import { HomePage } from './pages/HomePage'
import { Layout } from './pages/Layout'
import { Loading } from './pages/Loading'
import { LoginPage } from './pages/LoginPage'
import { PublicStudioPage } from './pages/PublicStudioPage'
import { RegisterPage } from './pages/RegisterPage'

// Telas carregadas SOB DEMANDA (ADR 0018, orçamento de peso): o visitante da página pública e quem só entra no app baixam só o essencial; cada tela da equipe,
// do administrador e do agendamento chega quando é aberta. Entrar, cadastro, Início, página pública e troca de senha ficam no pacote inicial.
const lazyPage = <K extends string>(load: () => Promise<Record<K, ComponentType>>, name: K) => lazy(() => load().then((m) => ({ default: m[name] })))
const AvailabilityPage = lazyPage(() => import('./pages/provider/AvailabilityPage'), 'AvailabilityPage')
const BusinessPage = lazyPage(() => import('./pages/provider/BusinessPage'), 'BusinessPage')
const ServicesPage = lazyPage(() => import('./pages/provider/ServicesPage'), 'ServicesPage')
const TeamPage = lazyPage(() => import('./pages/provider/TeamPage'), 'TeamPage')
const CreateProviderPage = lazyPage(() => import('./pages/admin/CreateProviderPage'), 'CreateProviderPage')
const UsersPage = lazyPage(() => import('./pages/admin/UsersPage'), 'UsersPage')
const AgendaPage = lazyPage(() => import('./pages/agenda/AgendaPage'), 'AgendaPage')
const QueuePage = lazyPage(() => import('./pages/agenda/QueuePage'), 'QueuePage')
const BookPage = lazyPage(() => import('./pages/customer/BookPage'), 'BookPage')
const MyAppointmentsPage = lazyPage(() => import('./pages/customer/MyAppointmentsPage'), 'MyAppointmentsPage')
const PetsPage = lazyPage(() => import('./pages/customer/PetsPage'), 'PetsPage')
const ProfilePage = lazyPage(() => import('./pages/ProfilePage'), 'ProfilePage')

/** O espaço da tela fica no lugar enquanto o pacote dela chega (a casca do app não some). */
function Lazy({ children }: { children: ReactNode }) {
  return <Suspense fallback={<Loading />}>{children}</Suspense>
}

// Página de demonstração da fundação visual (ADR 0018, R2): só em desenvolvimento. Em produção o valor é null e o código nem entra no pacote.
const UiDemoPage = import.meta.env.DEV ? lazy(() => import('./ui/DemoPage')) : null

export function App() {
  return (
    <Routes>
      {UiDemoPage && (
        <Route
          path="/__ui"
          element={
            <Suspense fallback={null}>
              <UiDemoPage />
            </Suspense>
          }
        />
      )}
      <Route element={<Layout />}>
        {/* Página pública do estúdio (ADR 0015): aberta a todos, com ou sem login. */}
        <Route path="/p/:slug" element={<PublicStudioPage />} />
        <Route element={<PublicOnly />}>
          <Route path="/entrar" element={<LoginPage />} />
          <Route path="/cadastro" element={<RegisterPage />} />
        </Route>
        <Route element={<RequireAuth />}>
          <Route path="/" element={<HomePage />} />
          <Route path="/trocar-senha" element={<ChangePasswordPage />} />
          <Route path="/perfil" element={<Lazy><ProfilePage /></Lazy>} />
          <Route element={<RequireRole roles={['PROVIDER']} />}>
            <Route path="/meu-negocio" element={<Lazy><BusinessPage /></Lazy>} />
            <Route path="/servicos" element={<Lazy><ServicesPage /></Lazy>} />
            <Route path="/expediente" element={<Lazy><AvailabilityPage /></Lazy>} />
            <Route path="/equipe" element={<Lazy><TeamPage /></Lazy>} />
          </Route>
          <Route element={<RequireRole roles={['ADMIN']} />}>
            <Route path="/admin/contas" element={<Lazy><UsersPage /></Lazy>} />
            <Route path="/admin/prestadores/novo" element={<Lazy><CreateProviderPage /></Lazy>} />
          </Route>
          <Route element={<RequireRole roles={['CUSTOMER']} />}>
            <Route path="/agendar" element={<Lazy><BookPage /></Lazy>} />
            <Route path="/meus-agendamentos" element={<Lazy><MyAppointmentsPage /></Lazy>} />
            <Route path="/pets" element={<Lazy><PetsPage /></Lazy>} />
          </Route>
          <Route element={<RequireRole roles={['PROVIDER', 'ATTENDANT']} />}>
            <Route path="/agenda" element={<Lazy><AgendaPage /></Lazy>} />
            <Route path="/fila" element={<Lazy><QueuePage /></Lazy>} />
          </Route>
        </Route>
        <Route path="*" element={<NotFound />} />
      </Route>
    </Routes>
  )
}

function NotFound() {
  const { user } = useAuth()
  const content = (
    <section className="card narrow">
      <h1>Página não encontrada</h1>
      <p className="lead">O endereço não existe ou foi movido.</p>
      <Link to="/" className="button">
        Ir para o início
      </Link>
    </section>
  )
  return user ? content : <AuthLayout>{content}</AuthLayout>
}
