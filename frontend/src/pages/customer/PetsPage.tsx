import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { describeError } from '../../api/messages'
import { PET_LIMITS, petDescription, petsApi, type Pet } from '../../api/pets'
import { useAuth } from '../../auth/session'
import { Loading } from '../Loading'
import { RefreshWarning } from '../RefreshWarning'
import { useLoad } from '../useLoad'
import { useSingleFlight } from '../useSingleFlight'
import { PetForm } from './PetForm'

/**
 * Meus pets: cadastrar, editar, arquivar e reativar os próprios pets (a API usa a identidade do token). Arquivar não apaga: o pet
 * some das escolhas para agendar, mas os agendamentos já feitos continuam valendo e guardam os dados do pet. Os limites (20 ativos)
 * e as regras de texto são da API; as recusas aparecem aqui com o motivo.
 */
export function PetsPage() {
  const { request } = useAuth()
  const api = useMemo(() => petsApi(request), [request])
  const [showArchived, setShowArchived] = useState(false)
  const load = useCallback(() => api.list(showArchived), [api, showArchived])
  const { data, error, loading, reload } = useLoad<Pet[]>(load)
  const [notice, setNotice] = useState<string | null>(null)
  // O erro de uma ação mora na LISTA (não na linha): a linha pode sumir (arquivar) ou mudar de lugar depois de recarregar.
  const [problem, setProblem] = useState<string | null>(null)
  const headingRef = useRef<HTMLHeadingElement>(null)

  if (loading && !data) return <Loading />
  if (error && !data) return <p role="alert" className="error">{describeError(error)}</p>

  const activeCount = data?.filter((pet) => pet.active).length ?? 0
  return (
    <section className="card">
      <h1 ref={headingRef} tabIndex={-1}>
        Meus pets
      </h1>
      {error !== undefined && data && <RefreshWarning onRetry={reload} />}
      <p className="hint">
        Os animais que você leva ao estúdio. {activeCount} de {PET_LIMITS.activePets} pets ativos.
      </p>
      <p role="status" className="notice" hidden={!notice}>
        {notice}
      </p>
      <p role="alert" className="error" hidden={!problem}>
        {problem}
      </p>
      <label className="check">
        <input type="checkbox" checked={showArchived} onChange={(e) => setShowArchived(e.target.checked)} /> Mostrar arquivados
      </label>
      {data && data.length === 0 && <p className="notice">Você ainda não tem pets{showArchived ? '' : ' ativos'}. Cadastre o primeiro abaixo.</p>}
      <ul className="rows">
        {data?.map((pet) => (
          <PetRow
            key={pet.id}
            pet={pet}
            onChanged={reload}
            setNotice={setNotice}
            setProblem={setProblem}
            onRowGone={() => headingRef.current?.focus()}
            archivedVisible={showArchived}
          />
        ))}
      </ul>
      <h2>Novo pet</h2>
      <PetForm
        idPrefix="new-pet"
        submitLabel="Cadastrar pet"
        busyLabel="Cadastrando…"
        onSubmit={async (input) => {
          setProblem(null)
          const created = await api.create(input)
          setNotice(`${created.name} cadastrado.`)
          await reload()
        }}
      />
    </section>
  )
}

function PetRow({
  pet,
  archivedVisible,
  onChanged,
  setNotice,
  setProblem,
  onRowGone,
}: {
  pet: Pet
  archivedVisible: boolean
  onChanged: () => Promise<void>
  setNotice: (message: string | null) => void
  setProblem: (message: string | null) => void
  onRowGone: () => void
}) {
  const { request } = useAuth()
  const api = useMemo(() => petsApi(request), [request])
  const [editing, setEditing] = useState(false)
  const [confirming, setConfirming] = useState(false)
  const [busy, setBusy] = useState(false)
  const once = useSingleFlight()
  // Foco (teclado e leitor de tela): a confirmação abre em "Voltar" (a opção segura); ao fechar volta para "Arquivar".
  const rowRef = useRef<HTMLLIElement>(null)
  const archiveRef = useRef<HTMLButtonElement>(null)
  const backRef = useRef<HTMLButtonElement>(null)
  const wasConfirming = useRef(false)
  useEffect(() => {
    if (confirming) backRef.current?.focus()
    else if (wasConfirming.current) archiveRef.current?.focus()
    wasConfirming.current = confirming
  }, [confirming])

  async function run(action: () => Promise<Pet>, done: (updated: Pet) => string, goneAfter: boolean) {
    await once(async () => {
      setProblem(null)
      setNotice(null)
      setBusy(true)
      try {
        const updated = await action()
        setConfirming(false)
        wasConfirming.current = false
        setNotice(done(updated))
        if (goneAfter) onRowGone()
        else rowRef.current?.focus()
        await onChanged()
      } catch (e) {
        setProblem(describeError(e))
        setConfirming(false)
        // O pet pode ter mudado ou sumido no servidor: refaz a lista para a tela não mostrar um estado velho.
        await onChanged().catch(() => undefined)
      } finally {
        setBusy(false)
      }
    })
  }

  if (editing) {
    return (
      <li className="row" ref={rowRef} tabIndex={-1}>
        <PetForm
          idPrefix={`pet-${pet.id}`}
          pet={pet}
          submitLabel="Salvar"
          busyLabel="Salvando…"
          onCancel={() => setEditing(false)}
          onSubmit={async (input) => {
            setProblem(null)
            const updated = await api.update(pet.id, input)
            setEditing(false)
            setNotice(`${updated.name} atualizado.`)
            await onChanged()
          }}
        />
      </li>
    )
  }

  return (
    <li className="row" ref={rowRef} tabIndex={-1} data-active={pet.active}>
      <span className="pet-info">
        <strong>{pet.name}</strong> <span className="appt-service">{petDescription(pet)}</span>{' '}
        {!pet.active && <span className="badge">Arquivado</span>}
        {pet.notes && <span className="pet-notes">Observações: {pet.notes}</span>}
      </span>
      <span className="actions">
        {!confirming ? (
          <>
            <button type="button" className="button secondary" disabled={busy} onClick={() => setEditing(true)}>
              Editar
            </button>
            {pet.active ? (
              <button type="button" className="button secondary" ref={archiveRef} disabled={busy} onClick={() => setConfirming(true)}>
                Arquivar
              </button>
            ) : (
              <button
                type="button"
                className="button secondary"
                disabled={busy}
                onClick={() => run(() => api.reactivate(pet.id), (p) => `${p.name} reativado.`, false)}
              >
                {busy ? 'Reativando…' : 'Reativar'}
              </button>
            )}
          </>
        ) : (
          <>
            <span>
              Arquivar {pet.name}? Agendamentos já feitos continuam valendo, mas você não poderá marcar novos horários com ele enquanto estiver arquivado.
            </span>
            <button type="button" className="button" disabled={busy} onClick={() => run(() => api.archive(pet.id), (p) => `${p.name} arquivado.`, !archivedVisible)}>
              {busy ? 'Arquivando…' : 'Confirmar arquivamento'}
            </button>
            <button type="button" className="button secondary" ref={backRef} disabled={busy} onClick={() => setConfirming(false)}>
              Voltar
            </button>
          </>
        )}
      </span>
    </li>
  )
}
