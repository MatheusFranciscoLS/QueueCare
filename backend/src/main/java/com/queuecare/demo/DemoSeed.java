package com.queuecare.demo;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Os dados FICTÍCIOS da demonstração pública (ADR 0041): um estúdio com equipe, tutores, pets e agendamentos em todos os estados, para as telas terem o que mostrar.
 * Nomes, telefones e a chave Pix são inventados (domínio {@code .invalid} e números de exemplo), nunca de pessoas reais. É a mesma massa de dados que o
 * {@code npm run demo} local usa, trazida para o código de produção porque a demonstração pública precisa recriá-la sozinha.
 *
 * <p>Repor = apagar o que o estúdio fictício e os tutores fictícios têm e criar de novo. As CONTAS ficam (e sem senha utilizável: só se entra pelo botão da demonstração).
 * Tutores que visitantes cadastraram pelo balcão não podem ser apagados (o cadastro deles está na auditoria, que é só de acréscimo): são desligados do estúdio,
 * desativados e anonimizados, então nenhum nome ou telefone digitado por um visitante sobrevive à reposição.
 */
@Component
@ConditionalOnProperty(name = "queuecare.demo.enabled", havingValue = "true")
class DemoSeed {

	static final String DOMAIN = "@demo.invalid";
	static final String OWNER_EMAIL = "dono" + DOMAIN;
	static final String ATTENDANT_EMAIL = "atendente" + DOMAIN;
	static final String CUSTOMER_EMAIL = "tutora" + DOMAIN;
	private static final String JOAO_EMAIL = "joao" + DOMAIN;
	private static final String CARLA_EMAIL = "carla" + DOMAIN;
	private static final String PAULO_EMAIL = "paulo" + DOMAIN;
	private static final ZoneId ZONE = ZoneId.of("America/Sao_Paulo");

	private final JdbcTemplate jdbc;
	private final PasswordEncoder passwordEncoder;
	private final Clock clock;

	DemoSeed(JdbcTemplate jdbc, PasswordEncoder passwordEncoder, Clock clock) {
		this.jdbc = jdbc;
		this.passwordEncoder = passwordEncoder;
		this.clock = clock;
	}

	/** Chamada dentro da transação do {@link DemoService}: tudo ou nada. */
	void reset() {
		wipe();
		seed();
	}

	private void wipe() {
		List<UUID> studio = jdbc.queryForList("SELECT p.id FROM providers p JOIN users u ON u.id = p.user_id WHERE u.email = ?", UUID.class, OWNER_EMAIL);
		if (studio.isEmpty()) {
			return; // primeira vez: não há o que apagar
		}
		UUID p = studio.get(0);
		String customers = "(SELECT id FROM users WHERE role = 'CUSTOMER' AND (email LIKE '%" + DOMAIN + "' OR created_by_provider_id = ?))";
		jdbc.update("DELETE FROM waitlist_entries WHERE provider_id = ? OR customer_id IN " + customers, p, p);
		jdbc.update("DELETE FROM appointment_events WHERE appointment_id IN (SELECT id FROM appointments WHERE provider_id = ? OR customer_id IN " + customers + ")", p, p);
		jdbc.update("DELETE FROM appointments WHERE provider_id = ? OR customer_id IN " + customers, p, p);
		jdbc.update("DELETE FROM pets WHERE customer_id IN " + customers, p);
		jdbc.update("DELETE FROM services WHERE provider_id = ?", p);
		jdbc.update("DELETE FROM availability_windows WHERE provider_id = ?", p);
		jdbc.update("DELETE FROM time_off WHERE provider_id = ?", p);
		jdbc.update("DELETE FROM provider_staff WHERE provider_id = ? AND user_id NOT IN (SELECT id FROM users WHERE email = ?)", p, ATTENDANT_EMAIL);
		jdbc.update("UPDATE users SET created_by_provider_id = NULL, active = false, name = 'Cadastro de visitante removido', phone = NULL, "
				+ "email = 'removido-' || id || '" + DOMAIN + "' WHERE created_by_provider_id = ?", p);
	}

	private void seed() {
		UUID owner = find(OWNER_EMAIL);
		// Senha ALEATÓRIA que ninguém conhece: a conta não tem como entrar pelo formulário de login, só pelo botão da demonstração.
		// Calculada uma vez só, na primeira reposição (BCrypt é lento no plano gratuito); depois reaproveita a das contas já criadas.
		String hash = owner != null ? jdbc.queryForObject("SELECT password_hash FROM users WHERE id = ?", String.class, owner)
				: passwordEncoder.encode(UUID.randomUUID().toString());
		if (owner == null) {
			owner = user(hash, "Helena Prado", OWNER_EMAIL, "PROVIDER");
		}
		UUID attendant = findOrCreate(hash, "Bruno Alves", ATTENDANT_EMAIL, "ATTENDANT");
		UUID marina = findOrCreate(hash, "Marina Souza", CUSTOMER_EMAIL, "CUSTOMER");
		UUID joao = findOrCreate(hash, "João Ferreira", JOAO_EMAIL, "CUSTOMER");
		UUID carla = findOrCreate(hash, "Carla Mendes", CARLA_EMAIL, "CUSTOMER");
		findOrCreate(hash, "Paulo Ribeiro", PAULO_EMAIL, "CUSTOMER"); // tutor novo, sem pets nem agendamentos (estados vazios)
		jdbc.update("UPDATE users SET phone = ? WHERE id = ?", "5511999990001", marina);
		jdbc.update("UPDATE users SET phone = ? WHERE id = ?", "5511999990002", joao);
		jdbc.update("UPDATE users SET phone = ? WHERE id = ?", "5511999990003", carla);

		List<UUID> existing = jdbc.queryForList("SELECT id FROM providers WHERE user_id = ?", UUID.class, owner);
		UUID provider;
		if (existing.isEmpty()) {
			provider = jdbc.queryForObject("INSERT INTO providers (user_id, business_name, time_zone, minimum_notice_minutes, cancellation_deadline_minutes, slug, published) "
					+ "VALUES (?, 'Estúdio Bem-te-vi', 'America/Sao_Paulo', 60, 120, 'estudio-bem-te-vi', true) RETURNING id", UUID.class, owner);
		} else {
			provider = existing.get(0);
			jdbc.update("UPDATE providers SET business_name = 'Estúdio Bem-te-vi', time_zone = 'America/Sao_Paulo', minimum_notice_minutes = 60, cancellation_deadline_minutes = 120, "
					+ "slug = 'estudio-bem-te-vi', published = true WHERE id = ?", provider);
		}
		jdbc.update("INSERT INTO provider_staff (provider_id, user_id) SELECT ?, ? WHERE NOT EXISTS (SELECT 1 FROM provider_staff WHERE provider_id = ? AND user_id = ?)",
				provider, attendant, provider, attendant);
		for (int day = 1; day <= 7; day++) {
			jdbc.update("INSERT INTO availability_windows (provider_id, day_of_week, start_time, end_time) VALUES (?, ?, '08:00', '12:00'), (?, ?, '14:00', '18:00')", provider, day, provider, day);
		}
		UUID bath = service(provider, "Banho", 60);
		UUID hygienic = service(provider, "Tosa higiênica", 45);
		UUID full = service(provider, "Banho e tosa", 90);
		service(provider, "Hidratação", 30);
		jdbc.update("INSERT INTO time_off (provider_id, starts_on, ends_on, reason) VALUES (?, CURRENT_DATE + 20, CURRENT_DATE + 21, 'Folga da equipe')", provider);

		UUID thor = pet(marina, "Thor", "DOG", "Golden Retriever", "LARGE", "Tem medo de secador. Não gosta de cheiro forte.");
		UUID mel = pet(marina, "Mel", "CAT", "Siamês", "SMALL", null);
		UUID bidu = pet(joao, "Bidu", "DOG", "Poodle", "SMALL", "Alérgico a um shampoo comum: usar o neutro.");
		UUID nina = pet(carla, "Nina", "DOG", "Vira-lata", "MEDIUM", null);
		String[] thorCopy = { "Thor", "DOG", "Golden Retriever", "LARGE", "Tem medo de secador. Não gosta de cheiro forte." };
		String[] melCopy = { "Mel", "CAT", "Siamês", "SMALL", null };
		String[] biduCopy = { "Bidu", "DOG", "Poodle", "SMALL", "Alérgico a um shampoo comum: usar o neutro." };
		String[] ninaCopy = { "Nina", "DOG", "Vira-lata", "MEDIUM", null };

		// Hoje: um de cada situação, para a Agenda e a Fila terem o que mostrar.
		appointment(provider, bath, marina, mel, melCopy, 0, LocalTime.of(8, 0), 60, "COMPLETED", marina, attendant);
		appointment(provider, full, joao, bidu, biduCopy, 0, LocalTime.of(9, 30), 90, "IN_PROGRESS", joao, attendant);
		appointment(provider, hygienic, carla, nina, ninaCopy, 0, LocalTime.of(11, 0), 45, "READY", carla, attendant);
		appointment(provider, bath, marina, mel, melCopy, 0, LocalTime.of(17, 0), 60, "CHECKED_IN", marina, attendant);
		appointment(provider, bath, marina, thor, thorCopy, 0, LocalTime.of(14, 0), 60, "SCHEDULED", marina, attendant);
		appointment(provider, full, carla, nina, ninaCopy, 0, LocalTime.of(15, 30), 90, "SCHEDULED", carla, attendant);
		appointment(provider, bath, joao, bidu, biduCopy, 0, LocalTime.of(17, 0), 60, "CANCELLED", joao, attendant);
		// Próximos dias (a semana e "Meus agendamentos").
		appointment(provider, bath, marina, thor, thorCopy, 1, LocalTime.of(9, 0), 60, "SCHEDULED", marina, attendant);
		appointment(provider, full, joao, bidu, biduCopy, 1, LocalTime.of(14, 0), 90, "SCHEDULED", joao, attendant);
		appointment(provider, hygienic, carla, nina, ninaCopy, 2, LocalTime.of(10, 30), 45, "SCHEDULED", carla, attendant);
		appointment(provider, bath, marina, mel, melCopy, 3, LocalTime.of(16, 0), 60, "SCHEDULED", marina, attendant);
		appointment(provider, full, marina, thor, thorCopy, -7, LocalTime.of(9, 0), 90, "COMPLETED", marina, attendant);

		// Ficha dos pets (ADR 0019) e a cópia dela nos agendamentos.
		jdbc.update("UPDATE pets SET coat = 'DOUBLE', weight_kg = 32.5, temperament = 'ANXIOUS', vaccinated = true, health_notes = 'Pele sensível: usar shampoo neutro.' WHERE id = ?", thor);
		jdbc.update("UPDATE pets SET coat = 'SHORT', weight_kg = 4.2, temperament = 'CALM', vaccinated = true WHERE id = ?", mel);
		jdbc.update("UPDATE pets SET coat = 'CURLY', weight_kg = 6.8, temperament = 'REACTIVE', vaccinated = false, health_notes = 'Alérgico a um shampoo comum.' WHERE id = ?", bidu);
		jdbc.update("UPDATE pets SET coat = 'MEDIUM', weight_kg = 14.0 WHERE id = ?", nina);
		// Preços (ADR 0019), em centavos; "Banho" varia por porte, "Hidratação" fica a combinar.
		jdbc.update("UPDATE services SET price_cents = 5000, price_medium_cents = 7000, price_large_cents = 9000 WHERE id = ?", bath);
		jdbc.update("UPDATE services SET price_cents = 4500 WHERE id = ?", hygienic);
		jdbc.update("UPDATE services SET price_cents = 9000, price_large_cents = 12000 WHERE id = ?", full);
		// Política e contato (ADR 0021), duração por porte e pelo (ADR 0020).
		jdbc.update("UPDATE providers SET phone = '5511987654321', cancellation_deadline_minutes = 720 WHERE id = ?", provider);
		jdbc.update("UPDATE services SET duration_medium_minutes = 75, duration_large_minutes = 90, coat_extra_minutes = 30 WHERE id = ?", bath);
		jdbc.update("UPDATE services SET duration_large_minutes = 60, coat_extra_minutes = 15 WHERE id = ?", hygienic);
		// Sinal por Pix (ADR 0023): a Carla tem um sinal pendente e o João um já recebido.
		jdbc.update("UPDATE providers SET pix_key = 'estudio@bemtevi.invalid', deposit_cents = 3000, deposit_rule = 'NEW_OR_REPEAT_NO_SHOW' WHERE id = ?", provider);
		jdbc.update("UPDATE appointments SET deposit_cents = 3000, deposit_status = 'REQUESTED' WHERE provider_id = ? AND customer_id = ? AND status = 'SCHEDULED' AND starts_at > now()", provider, carla);
		jdbc.update("UPDATE appointments SET deposit_cents = 3000, deposit_status = 'RECEIVED', deposit_received_at = now() WHERE provider_id = ? AND customer_id = ? AND status = 'SCHEDULED' AND starts_at > now()", provider, joao);
		// Faltas (ADR 0021): a Carla faltou uma vez; ontem ficou um agendamento sem desfecho (a equipe vê o botão Faltou); há um lembrete já enviado.
		appointment(provider, hygienic, carla, nina, ninaCopy, -3, LocalTime.of(10, 0), 45, "NO_SHOW", carla, attendant);
		appointment(provider, bath, joao, bidu, biduCopy, -1, LocalTime.of(10, 0), 60, "SCHEDULED", joao, attendant);
		jdbc.update("UPDATE appointments SET reminded_at = now() - interval '2 hours' WHERE provider_id = ? AND status = 'SCHEDULED' AND starts_at > now() AND starts_at < now() + interval '48 hours' AND customer_id = ?", provider, marina);
		jdbc.update("UPDATE appointments a SET price_cents = COALESCE(CASE a.pet_size WHEN 'MEDIUM' THEN s.price_medium_cents WHEN 'LARGE' THEN s.price_large_cents END, s.price_cents) "
				+ "FROM services s WHERE a.service_id = s.id AND a.provider_id = ?", provider);
		jdbc.update("UPDATE appointments a SET pet_coat = p.coat, pet_weight_kg = p.weight_kg, pet_temperament = p.temperament, pet_health_notes = p.health_notes, "
				+ "pet_vaccinated = p.vaccinated FROM pets p WHERE a.pet_id = p.id AND a.provider_id = ?", provider);
	}

	private UUID find(String email) {
		List<UUID> ids = jdbc.queryForList("SELECT id FROM users WHERE email = ?", UUID.class, email);
		return ids.isEmpty() ? null : ids.get(0);
	}

	private UUID findOrCreate(String hash, String name, String email, String role) {
		UUID id = find(email);
		if (id != null) {
			return id;
		}
		return user(hash, name, email, role);
	}

	private UUID user(String hash, String name, String email, String role) {
		return jdbc.queryForObject("INSERT INTO users (name, email, password_hash, role, active, must_change_password) VALUES (?, ?, ?, ?, true, false) RETURNING id", UUID.class, name, email, hash, role);
	}

	private UUID service(UUID provider, String name, int minutes) {
		return jdbc.queryForObject("INSERT INTO services (provider_id, name, duration_minutes) VALUES (?, ?, ?) RETURNING id", UUID.class, provider, name, minutes);
	}

	private UUID pet(UUID owner, String name, String species, String breed, String size, String notes) {
		return jdbc.queryForObject("INSERT INTO pets (customer_id, name, species, breed, size, notes) VALUES (?, ?, ?, ?, ?, ?) RETURNING id", UUID.class, owner, name, species, breed, size, notes);
	}

	/** Um agendamento, com a cópia do pet e os eventos coerentes com a situação (a fila lê o evento de chegada). */
	private void appointment(UUID provider, UUID service, UUID customer, UUID pet, String[] copy, int dayOffset, LocalTime start, int minutes, String status, UUID actor, UUID staff) {
		Instant starts = LocalDate.now(clock.withZone(ZONE)).plusDays(dayOffset).atTime(start).atZone(ZONE).toInstant();
		UUID id = jdbc.queryForObject("INSERT INTO appointments (provider_id, service_id, customer_id, starts_at, ends_at, status, pet_id, pet_name, pet_species, pet_breed, pet_size, pet_notes) "
				+ "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id", UUID.class, provider, service, customer, Timestamp.from(starts), Timestamp.from(starts.plusSeconds(minutes * 60L)), status, pet,
				copy[0], copy[1], copy[2], copy[3], copy[4]);
		event(id, actor, "SCHEDULED", starts.minusSeconds(3 * 86400L));
		boolean arrived = List.of("CHECKED_IN", "IN_PROGRESS", "READY", "COMPLETED").contains(status);
		boolean started = List.of("IN_PROGRESS", "READY", "COMPLETED").contains(status);
		boolean ready = List.of("READY", "COMPLETED").contains(status);
		if (arrived) {
			event(id, staff, "CHECKED_IN", starts.minusSeconds(600));
		}
		if (started) {
			event(id, staff, "STARTED", starts.minusSeconds(300));
		}
		if (ready) {
			event(id, staff, "READY", starts.plusSeconds(minutes * 60L - 60));
		}
		if (status.equals("COMPLETED")) {
			event(id, staff, "COMPLETED", starts.plusSeconds(minutes * 60L));
		}
		if (status.equals("CANCELLED")) {
			event(id, actor, "CANCELLED", starts.minusSeconds(86400L));
		}
	}

	private void event(UUID appointment, UUID actor, String type, Instant at) {
		jdbc.update("INSERT INTO appointment_events (appointment_id, actor_id, event_type, created_at) VALUES (?, ?, ?, ?)", appointment, actor, type, Timestamp.from(at));
	}
}
