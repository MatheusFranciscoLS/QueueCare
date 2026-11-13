# Modelo de dados (inicial)

Rascunho de partida. Claude modela as entidades e relacionamentos e atualiza este arquivo junto com as migrations Flyway; o ChatGPT revisa.

- `users` (id, name, email, password_hash, role, active). `role`: PROVIDER, ATTENDANT, CUSTOMER.
- `providers` (id, user_id **único**, nome do negócio, **time_zone IANA**, antecedência mínima, prazo de cancelamento). **Decisão (13/11/2026): tabela própria, relação 1:1 com `users`.** Motivo: `users` representa identidade e login; `providers` guarda as configurações do negócio (fuso, antecedência, prazo de cancelamento) e pode crescer sem inflar `users`. Recomendado por Claude e pelo ChatGPT (revisor independente); aprovado pelo Matheus.
- `services` (id, provider_id, name, duration_minutes, active).
- `availability_windows` (id, provider_id, day_of_week, start_time, end_time). Horários locais do prestador.
- `appointments` (id, provider_id, service_id, customer_id, starts_at, ends_at, status, created_at). Entidade central. `starts_at`/`ends_at` em `timestamptz`.
- `appointment_events` (id, appointment_id, actor_id, event_type, created_at, notes). Histórico: agendado, cancelado, check-in, concluído.

Relações: um prestador tem vários serviços e janelas; um agendamento pertence a um prestador, um serviço e um cliente; um agendamento tem vários eventos. O atendente é um usuário autorizado a administrar a agenda do prestador.
MVP com um prestador por ambiente; multiempresa só depois.

Integridade no banco (a definir no ADR 0002): constraint de exclusão para impedir sobreposição entre agendamentos ativos do mesmo prestador.

Pontos de integridade a considerar nas migrations:
- `users.email` único; `role` e `status` restritos a valores válidos (CHECK ou tipo enum).
- `appointments`: CHECK `ends_at > starts_at`; chaves estrangeiras para prestador, serviço e cliente.
- `availability_windows`: CHECK `end_time > start_time` e `day_of_week` entre 1 e 7; sem janelas sobrepostas no mesmo dia do mesmo prestador.
- `providers.user_id` único (relação 1:1 com `users`, se a tabela própria for adotada).
