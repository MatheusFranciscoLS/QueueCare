package com.queuecare.dev;

import com.queuecare.QueuecareApiApplication;
import com.queuecare.database.PostgresTestSupport;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.UUID;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * SOMENTE DESENVOLVIMENTO E VERIFICAÇÃO VISUAL (ADR 0018, seção 9): sobe a API com um PostgreSQL EMBUTIDO (o mesmo dos testes, sem Docker) e
 * dados FICTÍCIOS de demonstração: um estúdio com equipe, clientes, pets e agendamentos em todos os estados. Fica em {@code src/test}: nunca vai
 * para a imagem de produção. Todas as contas têm a mesma senha de teste (veja {@link #PASSWORD}); nada aqui é segredo.
 *
 * <p>Uso: {@code ./mvnw -o test-compile} e, no diretório {@code frontend}, {@code npm run demo} (que inicia este programa e o Vite).
 */
public final class LocalDevServer {

	/** Senha FICTÍCIA de todas as contas de demonstração (montada por partes, como nos testes, para não parecer um segredo ao varredor). */
	public static final String PASSWORD = String.join("-", "Senha", "Demo", "12345");
	static final ZoneId ZONE = ZoneId.of("America/Sao_Paulo");

	private LocalDevServer() {
	}

	public static void main(String[] args) throws Exception {
		String url = PostgresTestSupport.newEmptyDatabaseJdbcUrl();
		System.setProperty("spring.datasource.url", url);
		System.setProperty("spring.datasource.username", "postgres");
		System.setProperty("spring.datasource.password", "teste");
		System.setProperty("spring.flyway.enabled", "true");
		// Segredo ALEATÓRIO a cada subida (nada literal no código): as sessões do ambiente de demonstração não sobrevivem a um reinício, como esperado.
		byte[] secret = new byte[32];
		new java.security.SecureRandom().nextBytes(secret);
		System.setProperty("queuecare.jwt.secret", java.util.Base64.getEncoder().encodeToString(secret));
		System.setProperty("queuecare.security.bcrypt-strength", "4");
		System.setProperty("queuecare.cors.allowed-origins", "http://localhost:5173,http://127.0.0.1:5173,http://localhost:4173,http://127.0.0.1:4173");
		// A verificação automática faz muitas chamadas do mesmo endereço: limites altos só aqui.
		for (String key : new String[] { "login-per-minute", "register-per-minute", "slots-per-minute", "login-failures-per-email-and-ip", "password-change-attempts" }) {
			System.setProperty("queuecare.rate-limit." + key, "100000");
		}
		System.setProperty("server.port", System.getProperty("server.port", "8080"));

		ConfigurableApplicationContext context = SpringApplication.run(QueuecareApiApplication.class, args);
		// O Flyway já migrou (a API subiu): agora os dados de demonstração.
		try (Connection c = DriverManager.getConnection(url, "postgres", "teste")) {
			seed(c);
		}
		System.out.println("[dev] pronto: API em http://localhost:" + System.getProperty("server.port") + ", " + "contas em docs/redesign/verificacao.md");
		Runtime.getRuntime().addShutdownHook(new Thread(context::close));
	}

	private static UUID user(Connection c, String name, String email, String role) throws SQLException {
		try (PreparedStatement ps = c.prepareStatement("INSERT INTO users (name, email, password_hash, role, active, must_change_password) VALUES (?, ?, ?, ?, true, false) RETURNING id")) {
			ps.setString(1, name);
			ps.setString(2, email);
			ps.setString(3, new BCryptPasswordEncoder(4).encode(PASSWORD));
			ps.setString(4, role);
			return id(ps);
		}
	}

	private static UUID id(PreparedStatement ps) throws SQLException {
		try (var rs = ps.executeQuery()) {
			rs.next();
			return rs.getObject(1, UUID.class);
		}
	}

	private static void exec(Connection c, String sql, Object... args) throws SQLException {
		try (PreparedStatement ps = c.prepareStatement(sql)) {
			for (int i = 0; i < args.length; i++) {
				ps.setObject(i + 1, args[i]);
			}
			ps.executeUpdate();
		}
	}

	private static UUID pet(Connection c, UUID owner, String name, String species, String breed, String size, String notes) throws SQLException {
		try (PreparedStatement ps = c.prepareStatement("INSERT INTO pets (customer_id, name, species, breed, size, notes) VALUES (?, ?, ?, ?, ?, ?) RETURNING id")) {
			ps.setObject(1, owner);
			ps.setString(2, name);
			ps.setString(3, species);
			ps.setString(4, breed);
			ps.setString(5, size);
			ps.setString(6, notes);
			return id(ps);
		}
	}

	/** Um agendamento de demonstração, com a cópia do pet e os eventos coerentes com a situação (a fila lê o evento de chegada). */
	private static void appointment(Connection c, UUID provider, UUID service, UUID customer, UUID petId, String[] petCopy, int dayOffset, LocalTime start, int minutes, String status,
			UUID actor, UUID staff) throws SQLException {
		Instant starts = LocalDate.now(ZONE).plusDays(dayOffset).atTime(start).atZone(ZONE).toInstant();
		UUID appointmentId;
		try (PreparedStatement ps = c.prepareStatement("INSERT INTO appointments (provider_id, service_id, customer_id, starts_at, ends_at, status, pet_id, pet_name, pet_species, pet_breed, pet_size, pet_notes) "
				+ "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id")) {
			ps.setObject(1, provider);
			ps.setObject(2, service);
			ps.setObject(3, customer);
			ps.setTimestamp(4, Timestamp.from(starts));
			ps.setTimestamp(5, Timestamp.from(starts.plusSeconds(minutes * 60L)));
			ps.setString(6, status);
			ps.setObject(7, petId);
			for (int i = 0; i < 5; i++) {
				ps.setString(8 + i, petCopy[i]);
			}
			appointmentId = id(ps);
		}
		Instant created = starts.minusSeconds(3 * 86400L);
		exec(c, "INSERT INTO appointment_events (appointment_id, actor_id, event_type, created_at) VALUES (?, ?, 'SCHEDULED', ?)", appointmentId, actor, Timestamp.from(created));
		if (status.equals("CHECKED_IN") || status.equals("IN_PROGRESS") || status.equals("READY") || status.equals("COMPLETED")) {
			exec(c, "INSERT INTO appointment_events (appointment_id, actor_id, event_type, created_at) VALUES (?, ?, 'CHECKED_IN', ?)", appointmentId, staff, Timestamp.from(starts.minusSeconds(600)));
		}
		if (status.equals("IN_PROGRESS") || status.equals("READY") || status.equals("COMPLETED")) {
			exec(c, "INSERT INTO appointment_events (appointment_id, actor_id, event_type, created_at) VALUES (?, ?, 'STARTED', ?)", appointmentId, staff, Timestamp.from(starts.minusSeconds(300)));
		}
		if (status.equals("READY") || status.equals("COMPLETED")) {
			exec(c, "INSERT INTO appointment_events (appointment_id, actor_id, event_type, created_at) VALUES (?, ?, 'READY', ?)", appointmentId, staff, Timestamp.from(starts.plusSeconds(minutes * 60L - 60)));
		}
		if (status.equals("COMPLETED")) {
			exec(c, "INSERT INTO appointment_events (appointment_id, actor_id, event_type, created_at) VALUES (?, ?, 'COMPLETED', ?)", appointmentId, staff, Timestamp.from(starts.plusSeconds(minutes * 60L)));
		}
		if (status.equals("CANCELLED")) {
			exec(c, "INSERT INTO appointment_events (appointment_id, actor_id, event_type, created_at) VALUES (?, ?, 'CANCELLED', ?)", appointmentId, actor, Timestamp.from(starts.minusSeconds(86400L)));
		}
	}

	static void seed(Connection c) throws SQLException {
		user(c, "Administração Pelo & Ponto", "admin@pelo.test", "ADMIN");
		UUID owner = user(c, "Helena Prado", "helena@pelo.test", "PROVIDER");
		UUID attendant = user(c, "Bruno Alves", "bruno@pelo.test", "ATTENDANT");
		UUID marina = user(c, "Marina Souza", "marina@pelo.test", "CUSTOMER");
		UUID joao = user(c, "João Ferreira", "joao@pelo.test", "CUSTOMER");
		UUID carla = user(c, "Carla Mendes", "carla@pelo.test", "CUSTOMER");
		user(c, "Paulo Ribeiro", "paulo@pelo.test", "CUSTOMER"); // cliente novo, sem pets nem agendamentos nem WhatsApp (estados vazios)
		// WhatsApp fictício (ADR 0019): números de demonstração, nunca de pessoas reais.
		try (PreparedStatement ps = c.prepareStatement("UPDATE users SET phone = ? WHERE id = ?")) {
			for (Object[] row : new Object[][] { { "5511999990001", marina }, { "5511999990002", joao }, { "5511999990003", carla } }) {
				ps.setString(1, (String) row[0]);
				ps.setObject(2, row[1]);
				ps.executeUpdate();
			}
		}

		UUID provider;
		try (PreparedStatement ps = c.prepareStatement("INSERT INTO providers (user_id, business_name, time_zone, minimum_notice_minutes, cancellation_deadline_minutes, slug, published) "
				+ "VALUES (?, 'Estúdio Bem-te-vi', 'America/Sao_Paulo', 60, 120, 'estudio-bem-te-vi', true) RETURNING id")) {
			ps.setObject(1, owner);
			provider = id(ps);
		}
		exec(c, "INSERT INTO provider_staff (provider_id, user_id) VALUES (?, ?)", provider, attendant);
		for (int day = 1; day <= 7; day++) {
			exec(c, "INSERT INTO availability_windows (provider_id, day_of_week, start_time, end_time) VALUES (?, ?, '08:00', '12:00'), (?, ?, '14:00', '18:00')", provider, day, provider, day);
		}
		UUID bath = service(c, provider, "Banho", 60);
		UUID hygienic = service(c, provider, "Tosa higiênica", 45);
		UUID full = service(c, provider, "Banho e tosa", 90);
		service(c, provider, "Hidratação", 30);
		// Folga de demonstração (ADR 0024): dois dias fechados, longe o bastante para não atrapalhar os fluxos que agendam nos próximos dias.
		exec(c, "INSERT INTO time_off (provider_id, starts_on, ends_on, reason) VALUES (?, CURRENT_DATE + 20, CURRENT_DATE + 21, 'Folga da equipe')", provider);

		UUID thor = pet(c, marina, "Thor", "DOG", "Golden Retriever", "LARGE", "Tem medo de secador. Não gosta de cheiro forte.");
		UUID mel = pet(c, marina, "Mel", "CAT", "Siamês", "SMALL", null);
		UUID bidu = pet(c, joao, "Bidu", "DOG", "Poodle", "SMALL", "Alérgico a um shampoo comum: usar o neutro.");
		UUID nina = pet(c, carla, "Nina", "DOG", "Vira-lata", "MEDIUM", null);
		String[] thorCopy = { "Thor", "DOG", "Golden Retriever", "LARGE", "Tem medo de secador. Não gosta de cheiro forte." };
		String[] melCopy = { "Mel", "CAT", "Siamês", "SMALL", null };
		String[] biduCopy = { "Bidu", "DOG", "Poodle", "SMALL", "Alérgico a um shampoo comum: usar o neutro." };
		String[] ninaCopy = { "Nina", "DOG", "Vira-lata", "MEDIUM", null };

		// Hoje: um de cada situação, para a Agenda e a Fila terem o que mostrar.
		appointment(c, provider, bath, marina, mel, melCopy, 0, LocalTime.of(8, 0), 60, "COMPLETED", marina, attendant);
		appointment(c, provider, full, joao, bidu, biduCopy, 0, LocalTime.of(9, 30), 90, "IN_PROGRESS", joao, attendant);
		appointment(c, provider, hygienic, carla, nina, ninaCopy, 0, LocalTime.of(11, 0), 45, "READY", carla, attendant);
		appointment(c, provider, bath, marina, mel, melCopy, 0, LocalTime.of(17, 0), 60, "CHECKED_IN", marina, attendant);
		appointment(c, provider, bath, marina, thor, thorCopy, 0, LocalTime.of(14, 0), 60, "SCHEDULED", marina, attendant);
		appointment(c, provider, full, carla, nina, ninaCopy, 0, LocalTime.of(15, 30), 90, "SCHEDULED", carla, attendant);
		appointment(c, provider, bath, joao, bidu, biduCopy, 0, LocalTime.of(17, 0), 60, "CANCELLED", joao, attendant);
		// Próximos dias (a semana e "Meus agendamentos").
		appointment(c, provider, bath, marina, thor, thorCopy, 1, LocalTime.of(9, 0), 60, "SCHEDULED", marina, attendant);
		appointment(c, provider, full, joao, bidu, biduCopy, 1, LocalTime.of(14, 0), 90, "SCHEDULED", joao, attendant);
		appointment(c, provider, hygienic, carla, nina, ninaCopy, 2, LocalTime.of(10, 30), 45, "SCHEDULED", carla, attendant);
		appointment(c, provider, bath, marina, mel, melCopy, 3, LocalTime.of(16, 0), 60, "SCHEDULED", marina, attendant);
		appointment(c, provider, full, marina, thor, thorCopy, -7, LocalTime.of(9, 0), 90, "COMPLETED", marina, attendant);

		// Ficha dos pets (ADR 0019) e a cópia dela nos agendamentos: dados fictícios de demonstração.
		exec(c, "UPDATE pets SET coat = 'DOUBLE', weight_kg = 32.5, temperament = 'ANXIOUS', vaccinated = true, health_notes = 'Pele sensível: usar shampoo neutro.' WHERE id = ?", thor);
		exec(c, "UPDATE pets SET coat = 'SHORT', weight_kg = 4.2, temperament = 'CALM', vaccinated = true WHERE id = ?", mel);
		exec(c, "UPDATE pets SET coat = 'CURLY', weight_kg = 6.8, temperament = 'REACTIVE', vaccinated = false, health_notes = 'Alérgico a um shampoo comum.' WHERE id = ?", bidu);
		exec(c, "UPDATE pets SET coat = 'MEDIUM', weight_kg = 14.0 WHERE id = ?", nina);
		// Preços de demonstração (ADR 0019): em centavos; "Banho" varia por porte, "Hidratação" fica a combinar. O valor das reservas é o do porte do pet.
		exec(c, "UPDATE services SET price_cents = 5000, price_medium_cents = 7000, price_large_cents = 9000 WHERE id = ?", bath);
		exec(c, "UPDATE services SET price_cents = 4500 WHERE id = ?", hygienic);
		exec(c, "UPDATE services SET price_cents = 9000, price_large_cents = 12000 WHERE id = ?", full);
		// Política e contato (ADR 0021) e duração por porte e pelo (ADR 0020): o banho de porte grande ou de pelo longo leva mais tempo.
		exec(c, "UPDATE providers SET phone = '5511987654321', cancellation_deadline_minutes = 720 WHERE id = ?", provider);
		exec(c, "UPDATE services SET duration_medium_minutes = 75, duration_large_minutes = 90, coat_extra_minutes = 30 WHERE id = ?", bath);
		exec(c, "UPDATE services SET duration_large_minutes = 60, coat_extra_minutes = 15 WHERE id = ?", hygienic);
		// Sinal por Pix (ADR 0023): o estúdio de demonstração pede de quem ainda não foi atendido aqui ou já faltou 2 vezes; a Carla tem um sinal pendente e o João um já recebido.
		exec(c, "UPDATE providers SET pix_key = 'estudio@bemtevi.test', deposit_cents = 3000, deposit_rule = 'NEW_OR_REPEAT_NO_SHOW' WHERE id = ?", provider);
		exec(c, "UPDATE appointments SET deposit_cents = 3000, deposit_status = 'REQUESTED' WHERE provider_id = ? AND customer_id = ? AND status = 'SCHEDULED' AND starts_at > now()", provider, carla);
		exec(c, "UPDATE appointments SET deposit_cents = 3000, deposit_status = 'RECEIVED', deposit_received_at = now() WHERE provider_id = ? AND customer_id = ? AND status = 'SCHEDULED' AND starts_at > now()", provider, joao);
		// Faltas (ADR 0021): a Carla faltou uma vez; ontem ficou um agendamento sem desfecho (para a equipe ver o botão Faltou); amanhã há um lembrete já enviado.
		appointment(c, provider, hygienic, carla, nina, ninaCopy, -3, LocalTime.of(10, 0), 45, "NO_SHOW", carla, attendant);
		appointment(c, provider, bath, joao, bidu, biduCopy, -1, LocalTime.of(10, 0), 60, "SCHEDULED", joao, attendant);
		exec(c, "UPDATE appointments SET reminded_at = now() - interval '2 hours' WHERE provider_id = ? AND status = 'SCHEDULED' AND starts_at > now() AND starts_at < now() + interval '48 hours' AND customer_id = ?", provider, marina);
		exec(c, "UPDATE appointments a SET price_cents = COALESCE(CASE a.pet_size WHEN 'MEDIUM' THEN s.price_medium_cents WHEN 'LARGE' THEN s.price_large_cents END, s.price_cents) "
				+ "FROM services s WHERE a.service_id = s.id");
		exec(c, "UPDATE appointments a SET pet_coat = p.coat, pet_weight_kg = p.weight_kg, pet_temperament = p.temperament, pet_health_notes = p.health_notes, "
				+ "pet_vaccinated = p.vaccinated FROM pets p WHERE a.pet_id = p.id");
	}

	private static UUID service(Connection c, UUID provider, String name, int minutes) throws SQLException {
		try (PreparedStatement ps = c.prepareStatement("INSERT INTO services (provider_id, name, duration_minutes) VALUES (?, ?, ?) RETURNING id")) {
			ps.setObject(1, provider);
			ps.setString(2, name);
			ps.setInt(3, minutes);
			return id(ps);
		}
	}
}
