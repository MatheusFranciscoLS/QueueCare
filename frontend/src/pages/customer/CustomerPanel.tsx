import { useCallback, useMemo } from 'react'
import { Link } from 'react-router-dom'
import { bookingApi, type BookedAppointment } from '../../api/booking'
import { longDate, timeIn, todayIn } from '../../api/dates'
import { formatMoney } from '../../api/money'
import { petsApi, SPECIES_LABEL, type Pet } from '../../api/pets'
import { useAuth } from '../../auth/session'
import { PetAvatar } from '../../ui/components'
import { useLoad } from '../useLoad'

/** Situações de um agendamento que ainda não terminou (nem foi cancelado): o que o tutor precisa acompanhar. */
const OPEN = new Set(['SCHEDULED', 'CHECKED_IN', 'IN_PROGRESS', 'READY'])

const STATUS_TEXT: Record<string, string> = { SCHEDULED: 'Agendado', CHECKED_IN: 'Chegou', IN_PROGRESS: 'Em atendimento', READY: 'Pronto para retirada' }

/**
 * O painel do tutor no Início (ADR 0019, fatia 5): primeiro o que pede ação (o pet está pronto), depois o próximo agendamento, os pets e os
 * primeiros passos de quem ainda não tem pet. Cada parte carrega sozinha e, se falhar, simplesmente não aparece: os atalhos de baixo continuam.
 */
export function CustomerPanel() {
  const { request } = useAuth()
  const booking = useMemo(() => bookingApi(request), [request])
  const pets = useMemo(() => petsApi(request), [request])
  const loadMine = useCallback(() => booking.mine(0, 50), [booking])
  const loadPets = useCallback(() => pets.list(false), [pets])
  const { data: mine } = useLoad<BookedAppointment[]>(loadMine)
  const { data: myPets } = useLoad<Pet[]>(loadPets)

  const open = (mine ?? []).filter((a) => OPEN.has(a.status)).sort((a, b) => a.startsAt.localeCompare(b.startsAt))
  const ready = open.filter((a) => a.status === 'READY')
  const inService = open.filter((a) => a.status === 'IN_PROGRESS')
  const next = open.find((a) => a.status !== 'READY')

  return (
    <div className="panel">
      {ready.map((a) => (
        <p key={a.id} role="status" className="panel-alert" data-kind="ready">
          <strong>{a.pet?.name ?? 'O seu pet'} está pronto para retirada</strong>
          {a.businessName && <span> em {a.businessName}</span>}
        </p>
      ))}
      {inService.map((a) => (
        <p key={a.id} className="panel-alert" data-kind="working">
          <strong>{a.pet?.name ?? 'O seu pet'} está em atendimento</strong>
          {a.businessName && <span> em {a.businessName}</span>}
        </p>
      ))}
      {mine && next && <NextAppointment item={next} />}
      {mine && !next && myPets && myPets.length > 0 && (
        <div className="panel-card">
          <h2>Próximo agendamento</h2>
          <p>Você não tem nenhum horário marcado.</p>
          <p>
            <Link className="button" to="/agendar">
              Agendar um horário
            </Link>
          </p>
        </div>
      )}
      {myPets && myPets.length === 0 && (
        <div className="panel-card" data-kind="start">
          <h2>Comece por aqui</h2>
          <ol className="panel-steps">
            <li>
              <Link to="/pets">Cadastre o seu pet</Link> <span>nome, porte e pelo ajudam o estúdio a se preparar</span>
            </li>
            <li>
              <Link to="/agendar">Escolha o serviço e o horário</Link> <span>o valor aparece antes de confirmar</span>
            </li>
            <li>
              <span>Acompanhe aqui</span> <span>quando o seu pet estiver pronto, você vê nesta tela</span>
            </li>
          </ol>
        </div>
      )}
      {myPets && myPets.length > 0 && (
        <div className="panel-card">
          <h2>Meus pets</h2>
          <ul className="panel-pets">
            {myPets.map((p) => (
              <li key={p.id}>
                <PetAvatar species={p.species} />
                <span>
                  <strong>{p.name}</strong>
                  <small>{SPECIES_LABEL[p.species] ?? p.species}</small>
                </span>
              </li>
            ))}
          </ul>
          <p>
            <Link to="/pets">Gerenciar pets</Link>
          </p>
        </div>
      )}
    </div>
  )
}

function NextAppointment({ item }: { item: BookedAppointment }) {
  const zone = item.timeZone ?? 'America/Sao_Paulo'
  const day = todayIn(zone, new Date(item.startsAt))
  return (
    <div className="panel-card" data-kind="next">
      <h2>Próximo agendamento</h2>
      <div className="panel-next">
        {item.pet && <PetAvatar species={item.pet.species} />}
        <p>
          <strong>
            {longDate(day)}, {timeIn(item.startsAt, zone)}
          </strong>
          <span>
            {item.serviceName ?? 'Serviço'}
            {item.pet && <> · {item.pet.name}</>}
            {item.businessName && <> · {item.businessName}</>}
            {item.priceCents != null && <> · {formatMoney(item.priceCents)}</>}
          </span>
          <span className="badge" data-status={item.status}>
            {STATUS_TEXT[item.status] ?? item.status}
          </span>
        </p>
      </div>
      <p className="panel-links">
        <Link to="/meus-agendamentos">Ver ou remarcar</Link>
        <Link to="/agendar">Agendar outro horário</Link>
      </p>
    </div>
  )
}
