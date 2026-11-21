-- Dados FICTÍCIOS só para o ensaio de restauração (ADR 0028): um banco vazio restaurado "igual" não prova nada, então o ensaio planta linhas em várias tabelas
-- (com chaves estrangeiras e uma coluna JSON-like de texto longo) e confere que voltam idênticas. Nada disto vai para produção.
INSERT INTO users (id, name, email, password_hash, role, active) VALUES
  ('00000000-0000-0000-0000-0000000000a1', 'Dona do Ensaio', 'dona@ensaio.invalid', '$2a$10$ensaioensaioensaioensaioensaioensaioensaioensaioensaio', 'PROVIDER', true),
  ('00000000-0000-0000-0000-0000000000a2', 'Tutora do Ensaio', 'tutora@ensaio.invalid', '$2a$10$ensaioensaioensaioensaioensaioensaioensaioensaioensaio', 'CUSTOMER', true);

INSERT INTO providers (id, user_id, business_name, time_zone, minimum_notice_minutes, cancellation_deadline_minutes) VALUES
  ('00000000-0000-0000-0000-0000000000b1', '00000000-0000-0000-0000-0000000000a1', 'Estúdio do Ensaio', 'America/Sao_Paulo', 0, 120);

INSERT INTO availability_windows (provider_id, day_of_week, start_time, end_time)
  SELECT '00000000-0000-0000-0000-0000000000b1', d, '08:00', '18:00' FROM generate_series(1, 7) d;

INSERT INTO services (id, provider_id, name, duration_minutes) VALUES
  ('00000000-0000-0000-0000-0000000000c1', '00000000-0000-0000-0000-0000000000b1', 'Banho', 60);

INSERT INTO pets (id, customer_id, name, species, notes) VALUES
  ('00000000-0000-0000-0000-0000000000d1', '00000000-0000-0000-0000-0000000000a2', 'Pet do Ensaio', 'DOG', 'Observação com acentuação e emoji: ção ñ 🐶');

INSERT INTO appointments (id, provider_id, service_id, customer_id, starts_at, ends_at, status, pet_id, pet_name, pet_species) VALUES
  ('00000000-0000-0000-0000-0000000000e1', '00000000-0000-0000-0000-0000000000b1', '00000000-0000-0000-0000-0000000000c1', '00000000-0000-0000-0000-0000000000a2',
   '2030-01-10 12:00:00+00', '2030-01-10 13:00:00+00', 'SCHEDULED', '00000000-0000-0000-0000-0000000000d1', 'Pet do Ensaio', 'DOG');

INSERT INTO appointment_events (appointment_id, actor_id, event_type, created_at) VALUES
  ('00000000-0000-0000-0000-0000000000e1', '00000000-0000-0000-0000-0000000000a2', 'SCHEDULED', now());

INSERT INTO time_off (provider_id, starts_on, ends_on, reason) VALUES
  ('00000000-0000-0000-0000-0000000000b1', '2030-12-24', '2030-12-26', 'Folga do ensaio');
