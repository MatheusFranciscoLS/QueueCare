import type { AuthApi } from '../auth/session'

export type PetSpecies = 'DOG' | 'CAT'
export type PetSize = 'SMALL' | 'MEDIUM' | 'LARGE'
export type PetCoat = 'SHORT' | 'MEDIUM' | 'LONG' | 'CURLY' | 'DOUBLE'
export type PetTemperament = 'CALM' | 'ANXIOUS' | 'REACTIVE'

/** Pet do tutor (cadastro atual). As observações são do tutor e o prestador só as vê no agendamento em que atende. */
export interface Pet {
  id: string
  name: string
  species: PetSpecies
  breed: string | null
  size: PetSize | null
  notes: string | null
  /** Ficha do pet (ADR 0019): tudo opcional. */
  coat?: PetCoat | null
  weightKg?: number | null
  temperament?: PetTemperament | null
  healthNotes?: string | null
  vaccinated?: boolean | null
  active: boolean
}

/** Dados do formulário, como digitados: a API normaliza (espaços, quebras de linha) e valida de novo. */
export interface PetInput {
  name: string
  species: string
  breed: string
  size: string
  notes: string
  coat: string
  /** Como digitado ("12,5" ou "12.5"); vazio = não informado. */
  weightKg: string
  temperament: string
  healthNotes: string
  /** '' (não informado), 'yes' ou 'no'. */
  vaccinated: string
}

/** Uma visita do pet (ADR 0019, fatia 6): tudo o que a API guarda do agendamento, com o valor congelado na reserva. */
export interface PetVisit {
  id: string
  startsAt: string
  endsAt: string
  status: string
  providerId: string
  businessName: string | null
  timeZone: string | null
  serviceId: string
  serviceName: string | null
  priceCents: number | null
}

/** Pet como aparece no agendamento (cópia gravada no momento da reserva). Raça e observações só chegam onde a API as envia. */
export interface PetOnAppointment {
  name: string
  species: string
  breed?: string | null
  size?: string | null
  notes?: string | null
  /** Ficha (cópia da reserva); vem na agenda do dia e na fila, não na semana. */
  coat?: string | null
  weightKg?: number | null
  temperament?: string | null
  healthNotes?: string | null
  vaccinated?: boolean | null
}

export const SPECIES_LABEL: Record<string, string> = { DOG: 'Cachorro', CAT: 'Gato' }
export const SIZE_LABEL: Record<string, string> = { SMALL: 'Pequeno', MEDIUM: 'Médio', LARGE: 'Grande' }
export const COAT_LABEL: Record<string, string> = { SHORT: 'Curto', MEDIUM: 'Médio', LONG: 'Longo', CURLY: 'Cacheado', DOUBLE: 'Duplo (subpelo denso)' }
export const TEMPERAMENT_LABEL: Record<string, string> = { CALM: 'Tranquilo', ANXIOUS: 'Ansioso', REACTIVE: 'Reativo (pode reagir ou morder)' }

/** Peso digitado ("12,5", "12.5", "12") em número; vazio ou inválido volta nulo (a API confere a faixa de 0,5 a 120 kg). */
export function parseWeight(typed: string): number | null {
  const text = typed.trim().replace(',', '.')
  if (!text) return null
  const value = Number(text)
  return Number.isFinite(value) ? value : null
}

/** "32,5 kg" (vírgula decimal, como no Brasil). */
export function weightLabel(kg: number): string {
  return `${String(kg).replace('.', ',')} kg`
}

/** A ficha em uma linha: "Pelo duplo · 32,5 kg · Vacinas em dia". Só o que existe; vazio se não há nada. */
export function fichaSummary(pet: Pick<PetOnAppointment, 'coat' | 'weightKg' | 'vaccinated'>): string {
  const coat = pet.coat ? COAT_LABEL[pet.coat]?.split(' (')[0] : null
  return [coat ? `Pelo ${coat.toLowerCase()}` : null, pet.weightKg != null ? weightLabel(pet.weightKg) : null, pet.vaccinated === true ? 'Vacinas em dia' : pet.vaccinated === false ? 'Vacinas pendentes' : null]
    .filter(Boolean)
    .join(' · ')
}

/** Limites do cadastro (a API os repete e o banco também). */
export const PET_LIMITS = { name: 80, breed: 80, notes: 500, healthNotes: 300, activePets: 20 } as const

/** "Cachorro · Poodle · Médio": só o que existe. */
export function petDescription(pet: Pick<PetOnAppointment, 'species' | 'breed' | 'size'>): string {
  return [SPECIES_LABEL[pet.species] ?? pet.species, pet.breed, pet.size ? (SIZE_LABEL[pet.size] ?? pet.size) : null].filter(Boolean).join(' · ')
}

type Request = AuthApi['request']

/** Cadastro de pets do próprio cliente. O dono vem do token: nenhuma chamada envia o id do cliente. */
/** O corpo da requisição a partir do que foi digitado (usado também pela equipe, ao cadastrar o pet de um tutor do balcão, ADR 0033). */
export function petBody(input: PetInput) {
  return {
    name: input.name,
    species: input.species,
    breed: input.breed,
    size: input.size,
    notes: input.notes,
    coat: input.coat,
    weightKg: parseWeight(input.weightKg),
    temperament: input.temperament,
    healthNotes: input.healthNotes,
    vaccinated: input.vaccinated === 'yes' ? true : input.vaccinated === 'no' ? false : null,
  }
}

export function petsApi(request: Request) {
  const body = petBody
  return {
    list: (includeArchived = false) => request<Pet[]>(includeArchived ? '/api/customer/pets?includeArchived=true' : '/api/customer/pets'),
    create: (input: PetInput) => request<Pet>('/api/customer/pets', { method: 'POST', body: body(input) }),
    update: (id: string, input: PetInput) => request<Pet>(`/api/customer/pets/${encodeURIComponent(id)}`, { method: 'PUT', body: body(input) }),
    /** Visitas do pet, da mais recente para a mais antiga (até 50). */
    history: (id: string) => request<PetVisit[]>(`/api/customer/pets/${encodeURIComponent(id)}/history`),
    archive: (id: string) => request<Pet>(`/api/customer/pets/${encodeURIComponent(id)}/archive`, { method: 'POST' }),
    reactivate: (id: string) => request<Pet>(`/api/customer/pets/${encodeURIComponent(id)}/reactivate`, { method: 'POST' }),
  }
}
