# Modelo de dados (inicial)

Rascunho de partida. Claude modela as entidades e relacionamentos e atualiza este arquivo junto com as migrations Flyway; o ChatGPT revisa.

- `users` (id, name, email, password_hash, role, active). `role`: PROVIDER, ATTENDANT, CUSTOMER.
- `providers` (id, user_id **único**, nome do negócio, **time_zone IANA**, antecedência mínima, prazo de cancelamento). **Decisão (13/11/2026): tabela própria, relação 1:1 com `users`.** Motivo: `users` representa identidade e login; `providers` guarda as configurações do negócio (fuso, antecedência, prazo de cancelamento) e pode crescer sem inflar `users`. Recomendado por Claude e pelo ChatGPT (revisor independente); aprovado pelo Matheus.
- `services` (id, provider_id, name, duration_minutes, active).
- `availability_windows` (id, provider_id, day_of_week, start_time, end_time). Horários locais do prestador.
- `appointments` (id, provider_id, service_id, customer_id, starts_at, ends_at, status, created_at). Entidade central. `starts_at`/`ends_at` em `timestamptz`.
- `pets` (id, customer_id, name, species DOG/CAT, breed, size SMALL/MEDIUM/LARGE, notes, active, created_at, updated_at). **V8 (ADR 0016):** o animal atendido, do tutor (CUSTOMER); arquivar em vez de apagar; limites de 20 ativos e 100 no total na aplicação; `CHECK`s recusam texto fora da forma normalizada. **V9 (PR 47):** `appointments` ganha `pet_id` (chave estrangeira composta com o cliente: pet de outro cliente é recusado pelo banco) e a cópia `pet_name`, `pet_species`, `pet_breed`, `pet_size`, `pet_notes`, que a agenda, a fila e "Meus agendamentos" leem; os agendamentos ainda SCHEDULED acompanham as edições do pet, os demais ficam congelados. **V10 (PR 49):** `legacy_without_pet` (escrita só pela migration, protegida por gatilho) e `CHECK (pet_id IS NOT NULL OR legacy_without_pet)`: agendamento novo sem pet é recusado pelo banco; os antigos continuam válidos.
- `appointment_events` (id, appointment_id, actor_id, event_type, created_at, notes). Histórico: agendado, cancelado, check-in, concluído.

Relações: um prestador tem vários serviços e janelas; um agendamento pertence a um prestador, um serviço e um cliente; um agendamento tem vários eventos. O atendente é um usuário autorizado a administrar a agenda do prestador.
MVP com um prestador por ambiente; multiempresa só depois.

Integridade no banco (a definir no ADR 0002): constraint de exclusão para impedir sobreposição entre agendamentos ativos do mesmo prestador.

Pontos de integridade a considerar nas migrations:
- `users.email` único; `role` e `status` restritos a valores válidos (CHECK ou tipo enum).
- `appointments`: CHECK `ends_at > starts_at`; chaves estrangeiras para prestador, serviço e cliente.
- `availability_windows`: CHECK `end_time > start_time` e `day_of_week` entre 1 e 7; sem janelas sobrepostas no mesmo dia do mesmo prestador.
- `providers.user_id` único (relação 1:1 com `users`, se a tabela própria for adotada).

## Esquema real
A fonte da verdade é a migration `backend/src/main/resources/db/migration/V1__schema_inicial.sql` (chaves UUID, `timestamptz`, `NOT NULL` explícito, exclusão restrita em chaves estrangeiras, FK composta serviço/prestador e a restrição de exclusão do ADR 0002). Explicação em `docs/estudo/banco-e-t3.md`.
