import type { AuthApi } from '../auth/session'

export type PetSpecies = 'DOG' | 'CAT'
export type PetSize = 'SMALL' | 'MEDIUM' | 'LARGE'

/** Pet do tutor (cadastro atual). As observações são do tutor e o prestador só as vê no agendamento em que atende. */
export interface Pet {
  id: string
  name: string
  species: PetSpecies
  breed: string | null
  size: PetSize | null
  notes: string | null
  active: boolean
}

/** Dados do formulário, como digitados: a API normaliza (espaços, quebras de linha) e valida de novo. */
export interface PetInput {
  name: string
  species: string
  breed: string
  size: string
  notes: string
}

/** Pet como aparece no agendamento (cópia gravada no momento da reserva). Raça e observações só chegam onde a API as envia. */
export interface PetOnAppointment {
  name: string
  species: string
  breed?: string | null
  size?: string | null
  notes?: string | null
}

export const SPECIES_LABEL: Record<string, string> = { DOG: 'Cachorro', CAT: 'Gato' }
export const SIZE_LABEL: Record<string, string> = { SMALL: 'Pequeno', MEDIUM: 'Médio', LARGE: 'Grande' }

/** Limites do cadastro (a API os repete e o banco também). */
export const PET_LIMITS = { name: 80, breed: 80, notes: 500, activePets: 20 } as const

/** "Cachorro · Poodle · Médio": só o que existe. */
export function petDescription(pet: Pick<PetOnAppointment, 'species' | 'breed' | 'size'>): string {
  return [SPECIES_LABEL[pet.species] ?? pet.species, pet.breed, pet.size ? (SIZE_LABEL[pet.size] ?? pet.size) : null].filter(Boolean).join(' · ')
}

type Request = AuthApi['request']

/** Cadastro de pets do próprio cliente. O dono vem do token: nenhuma chamada envia o id do cliente. */
export function petsApi(request: Request) {
  const body = (input: PetInput) => ({
    name: input.name,
    species: input.species,
    breed: input.breed,
    size: input.size,
    notes: input.notes,
  })
  return {
    list: (includeArchived = false) => request<Pet[]>(includeArchived ? '/api/customer/pets?includeArchived=true' : '/api/customer/pets'),
    create: (input: PetInput) => request<Pet>('/api/customer/pets', { method: 'POST', body: body(input) }),
    update: (id: string, input: PetInput) => request<Pet>(`/api/customer/pets/${encodeURIComponent(id)}`, { method: 'PUT', body: body(input) }),
    archive: (id: string) => request<Pet>(`/api/customer/pets/${encodeURIComponent(id)}/archive`, { method: 'POST' }),
    reactivate: (id: string) => request<Pet>(`/api/customer/pets/${encodeURIComponent(id)}/reactivate`, { method: 'POST' }),
  }
}
